package notify

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/event"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"golang.org/x/oauth2"
)

func TestEncryptedPushRetriesAndThenDelivers(t *testing.T) {
	var attempts atomic.Int32
	var bodies [][]byte
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		bodies = append(bodies, body)
		if attempts.Add(1) == 1 {
			http.Error(w, "temporary", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	defer endpoint.Close()

	db, err := store.Open(t.TempDir() + "/notify.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	vault, err := appcrypto.LoadOrCreate(t.TempDir() + "/master.key")
	if err != nil {
		t.Fatal(err)
	}
	service := NewService(db, vault, "")
	service.fcmEndpoint = endpoint.URL
	service.projectID = "valkyris-test"
	service.tokenSource = oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "test-access-token"})
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('camera','Door','10.0.0.2',x'01',x'01',x'01',?,?)`, now, now); err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.Exec(`INSERT INTO devices(id,name,token_hash,created_at,last_seen_at) VALUES('phone','Pixel',x'01',?,?)`, now, now); err != nil {
		t.Fatal(err)
	}
	secret := "device-side-secret"
	if err = service.Register(context.Background(), "phone", Registration{Token: "fcm-device-token", Secret: secret}); err != nil {
		t.Fatal(err)
	}
	events := event.NewService(db)
	created, err := events.Create(context.Background(), event.Event{CameraID: "camera", Type: "baby_cry", Confidence: .91, Metadata: map[string]any{"alarm": true}})
	if err != nil {
		t.Fatal(err)
	}
	if err = service.Enqueue(context.Background(), created); err != nil {
		t.Fatal(err)
	}
	service.deliverBatch(context.Background())
	var count int
	var next string
	if err = db.DB.QueryRow(`SELECT attempts,next_attempt_at FROM push_deliveries`).Scan(&count, &next); err != nil || count != 1 {
		t.Fatalf("retry was not persisted: attempts=%d next=%q err=%v", count, next, err)
	}
	_, _ = db.DB.Exec(`UPDATE push_deliveries SET next_attempt_at=?`, now)
	service.deliverBatch(context.Background())
	var delivered string
	if err = db.DB.QueryRow(`SELECT attempts,delivered_at FROM push_deliveries`).Scan(&count, &delivered); err != nil || count != 2 || delivered == "" {
		t.Fatalf("delivery was not persisted: attempts=%d delivered=%q err=%v", count, delivered, err)
	}
	if len(bodies) != 2 || strings.Contains(string(bodies[1]), "baby_cry") || !strings.Contains(string(bodies[1]), `"priority":"high"`) {
		t.Fatalf("push body leaked plaintext: %s", bodies[1])
	}
	var wrapper struct {
		Message struct {
			Token string            `json:"token"`
			Data  map[string]string `json:"data"`
		} `json:"message"`
	}
	if err = json.Unmarshal(bodies[1], &wrapper); err != nil {
		t.Fatal(err)
	}
	if wrapper.Message.Token != "fcm-device-token" {
		t.Fatalf("unexpected FCM token: %q", wrapper.Message.Token)
	}
	sealed, err := base64.RawURLEncoding.DecodeString(wrapper.Message.Data["ciphertext"])
	if err != nil {
		t.Fatal(err)
	}
	plain, err := openTestPayload(secret, sealed)
	if err != nil || !strings.Contains(string(plain), "baby_cry") || !strings.Contains(string(plain), `"target":"event"`) || strings.Contains(string(plain), "fcm-device-token") {
		t.Fatalf("unexpected decrypted payload %q: %v", plain, err)
	}
}

func TestRegisterRequiresFirebaseCredentials(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/notify.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	vault, err := appcrypto.LoadOrCreate(t.TempDir() + "/master.key")
	if err != nil {
		t.Fatal(err)
	}
	service := NewService(db, vault, "")
	err = service.Register(context.Background(), "phone", Registration{Token: "token", Secret: "secret"})
	if err == nil || !strings.Contains(err.Error(), "FCM is not configured") {
		t.Fatalf("expected missing Firebase configuration error, got %v", err)
	}
}

func TestNotificationTarget(t *testing.T) {
	for _, eventType := range []string{"motion", "person", "tamper"} {
		if got := notificationTarget(eventType); got != "camera" {
			t.Fatalf("%s should open camera, got %s", eventType, got)
		}
	}
	for _, eventType := range []string{"baby_cry", "smoke_alarm", "dog_bark"} {
		if got := notificationTarget(eventType); got != "event" {
			t.Fatalf("%s should open event, got %s", eventType, got)
		}
	}
}

func openTestPayload(secret string, sealed []byte) ([]byte, error) {
	key := sha256.Sum256([]byte(secret))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	var aead cipher.AEAD
	if aead, err = cipher.NewGCM(block); err != nil {
		return nil, err
	}
	return aead.Open(nil, sealed[:aead.NonceSize()], sealed[aead.NonceSize():], nil)
}
