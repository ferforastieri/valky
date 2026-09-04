package notify

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/event"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

type Service struct {
	store           *store.Store
	vault           *appcrypto.Vault
	http            *http.Client
	tokenSource     oauth2.TokenSource
	projectID       string
	fcmEndpoint     string
	credentialsFile string
	tokenMu         sync.Mutex
}
type Registration struct {
	Token  string `json:"token"`
	Secret string `json:"secret"`
}

func NewService(s *store.Store, v *appcrypto.Vault, credentialsFile string) *Service {
	return &Service{store: s, vault: v, http: &http.Client{Timeout: 10 * time.Second}, credentialsFile: credentialsFile, fcmEndpoint: "https://fcm.googleapis.com"}
}

func (s *Service) Register(ctx context.Context, deviceID string, r Registration) error {
	if r.Token == "" || r.Secret == "" {
		return fmt.Errorf("FCM token and secret are required")
	}
	if _, err := s.accessToken(ctx); err != nil {
		return err
	}
	endpoint, err := s.vault.EncryptString(r.Token)
	if err != nil {
		return err
	}
	secret, err := s.vault.EncryptString(r.Secret)
	if err != nil {
		return err
	}
	_, err = s.store.DB.ExecContext(ctx, `UPDATE devices SET push_endpoint_enc=?,push_secret_enc=? WHERE id=?`, endpoint, secret, deviceID)
	return err
}
func (s *Service) Enqueue(ctx context.Context, e event.Event) error {
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id FROM devices WHERE enabled=1 AND push_endpoint_enc IS NOT NULL`)
	if err != nil {
		return err
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	var devices []string
	for rows.Next() {
		var device string
		if err = rows.Scan(&device); err != nil {
			rows.Close()
			return err
		}
		devices = append(devices, device)
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return err
	}
	if err = rows.Close(); err != nil {
		return err
	}
	for _, device := range devices {
		_, err = s.store.DB.ExecContext(ctx, `INSERT INTO push_deliveries(id,event_id,device_id,next_attempt_at,created_at)VALUES(?,?,?,?,?)`, uuid.NewString(), e.ID, device, now, now)
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) Run(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.deliverBatch(ctx)
		}
	}
}
func (s *Service) deliverBatch(ctx context.Context) {
	rows, err := s.store.DB.QueryContext(ctx, `SELECT p.id,p.attempts,d.push_endpoint_enc,d.push_secret_enc,e.id,e.camera_id,e.type,e.confidence,e.occurred_at,e.metadata_json FROM push_deliveries p JOIN devices d ON d.id=p.device_id JOIN events e ON e.id=p.event_id WHERE p.delivered_at IS NULL AND p.next_attempt_at<=? AND p.attempts<10 ORDER BY p.created_at LIMIT 20`, time.Now().UTC().Format(time.RFC3339Nano))
	if err != nil {
		return
	}
	type item struct {
		id                                     string
		attempts                               int
		endpoint, secret                       []byte
		eventID, cameraID, eventType, occurred string
		metadata                               string
		confidence                             float64
	}
	var items []item
	for rows.Next() {
		var i item
		if rows.Scan(&i.id, &i.attempts, &i.endpoint, &i.secret, &i.eventID, &i.cameraID, &i.eventType, &i.confidence, &i.occurred, &i.metadata) == nil {
			items = append(items, i)
		}
	}
	_ = rows.Close()
	for _, i := range items {
		var metadata map[string]any
		_ = json.Unmarshal([]byte(i.metadata), &metadata)
		s.deliver(ctx, i.id, i.attempts, i.endpoint, i.secret, map[string]any{"eventId": i.eventID, "cameraId": i.cameraID, "type": i.eventType, "confidence": i.confidence, "occurredAt": i.occurred, "alarm": metadata["alarm"], "target": notificationTarget(i.eventType)})
	}
}

func notificationTarget(eventType string) string {
	if eventType == "motion" || eventType == "person" || eventType == "tamper" {
		return "camera"
	}
	return "event"
}
func (s *Service) deliver(ctx context.Context, id string, attempts int, endpointEnc, secretEnc []byte, payload any) {
	deviceToken, err := s.vault.DecryptString(endpointEnc)
	if err != nil {
		s.fail(ctx, id, attempts, err)
		return
	}
	secret, err := s.vault.DecryptString(secretEnc)
	if err != nil {
		s.fail(ctx, id, attempts, err)
		return
	}
	plain, _ := json.Marshal(payload)
	sealed, err := seal([]byte(secret), plain)
	if err != nil {
		s.fail(ctx, id, attempts, err)
		return
	}
	accessToken, err := s.accessToken(ctx)
	if err != nil {
		s.fail(ctx, id, attempts, err)
		return
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token":   deviceToken,
		"data":    map[string]string{"ciphertext": base64.RawURLEncoding.EncodeToString(sealed)},
		"android": map[string]any{"priority": "high", "ttl": "60s"},
	}})
	endpoint := strings.TrimRight(s.fcmEndpoint, "/") + "/v1/projects/" + s.projectID + "/messages:send"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		s.fail(ctx, id, attempts, err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)
	resp, err := s.http.Do(req)
	if err != nil {
		s.fail(ctx, id, attempts, err)
		return
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode/100 != 2 {
		s.fail(ctx, id, attempts, fmt.Errorf("push endpoint returned %s", resp.Status))
		return
	}
	_, _ = s.store.DB.ExecContext(ctx, `UPDATE push_deliveries SET delivered_at=?,attempts=attempts+1,last_error=NULL WHERE id=?`, time.Now().UTC().Format(time.RFC3339Nano), id)
}

func (s *Service) accessToken(ctx context.Context) (string, error) {
	s.tokenMu.Lock()
	defer s.tokenMu.Unlock()
	if s.tokenSource == nil {
		if s.credentialsFile == "" {
			return "", fmt.Errorf("FCM is not configured: VALKYRIS_FIREBASE_CREDENTIALS_FILE is empty")
		}
		data, err := os.ReadFile(s.credentialsFile)
		if err != nil {
			return "", fmt.Errorf("read Firebase credentials: %w", err)
		}
		credentials, err := google.CredentialsFromJSON(context.Background(), data, "https://www.googleapis.com/auth/firebase.messaging")
		if err != nil {
			return "", fmt.Errorf("parse Firebase credentials: %w", err)
		}
		if credentials.ProjectID == "" {
			return "", fmt.Errorf("Firebase credentials do not contain project_id")
		}
		s.projectID = credentials.ProjectID
		s.tokenSource = credentials.TokenSource
	}
	token, err := s.tokenSource.Token()
	if err != nil {
		return "", fmt.Errorf("authorize Firebase messaging: %w", err)
	}
	return token.AccessToken, nil
}
func (s *Service) fail(ctx context.Context, id string, attempts int, err error) {
	delay := time.Duration(1<<min(attempts, 8)) * time.Minute
	_, _ = s.store.DB.ExecContext(ctx, `UPDATE push_deliveries SET attempts=attempts+1,next_attempt_at=?,last_error=? WHERE id=?`, time.Now().UTC().Add(delay).Format(time.RFC3339Nano), err.Error(), id)
}
func seal(secret, plain []byte) ([]byte, error) {
	key := sha256.Sum256(secret)
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	return aead.Seal(nonce, nonce, plain, nil), nil
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

var _ = sql.ErrNoRows
