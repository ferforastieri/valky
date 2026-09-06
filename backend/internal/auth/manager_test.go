package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestPairingIsOneTimeAndTokenAuthenticates(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/auth.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	manager := NewManager(db, time.Minute)
	session, err := manager.CreatePairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	paired, err := manager.Pair(context.Background(), PairRequest{Code: strings.ToLower(session.Code), DeviceName: "Pixel", Locale: "en"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = manager.Pair(context.Background(), PairRequest{Code: session.Code, DeviceName: "Again"}); err == nil {
		t.Fatal("one-time pairing code was accepted twice")
	}
	if id, err := manager.Authenticate(context.Background(), paired.Token); err != nil || id != paired.DeviceID {
		t.Fatalf("token did not authenticate: id=%q err=%v", id, err)
	}

	handler := manager.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if DeviceID(r.Context()) != paired.DeviceID {
			t.Error("device ID was not propagated")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer "+paired.Token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent {
		t.Fatalf("authenticated request returned %d", response.Code)
	}
	adminOnly := manager.Middleware(manager.RequireAdmin(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})))
	forbidden := httptest.NewRecorder()
	adminOnly.ServeHTTP(forbidden, req)
	if forbidden.Code != http.StatusForbidden {
		t.Fatalf("invited device accessed administrator action: %d", forbidden.Code)
	}
}

func TestExpiredPairingCodeIsRejected(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/expired.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	manager := NewManager(db, -time.Second)
	session, err := manager.CreatePairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = manager.Pair(context.Background(), PairRequest{Code: session.Code, DeviceName: "Phone"}); err == nil {
		t.Fatal("expired pairing code was accepted")
	}
}

func TestAdministratorLoginAndAuthorization(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/admin.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	manager := NewManager(db, time.Minute)
	initialized, err := manager.AdminInitialized(context.Background())
	if err != nil || initialized {
		t.Fatalf("fresh installation has unexpected admin state: initialized=%v err=%v", initialized, err)
	}
	logged, err := manager.BootstrapAdmin(context.Background(), LoginRequest{Password: "correct horse battery staple", DeviceName: "Pixel", Locale: "pt-BR"})
	if err != nil || !logged.Admin {
		t.Fatalf("administrator bootstrap failed: response=%+v err=%v", logged, err)
	}
	if _, err = manager.BootstrapAdmin(context.Background(), LoginRequest{Password: "another secure password", DeviceName: "Other"}); err == nil {
		t.Fatal("second administrator bootstrap was accepted")
	}
	if _, err = manager.LoginAdmin(context.Background(), LoginRequest{Password: "wrong", DeviceName: "Pixel"}); err == nil {
		t.Fatal("wrong administrator password was accepted")
	}
	second, err := manager.LoginAdmin(context.Background(), LoginRequest{Password: "correct horse battery staple", DeviceName: "Tablet", Locale: "pt-BR"})
	if err != nil || !second.Admin {
		t.Fatalf("administrator login failed: response=%+v err=%v", second, err)
	}

	handler := manager.Middleware(manager.RequireAdmin(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !IsAdmin(r.Context()) {
			t.Fatal("administrator role was not propagated")
		}
		w.WriteHeader(http.StatusNoContent)
	})))
	req := httptest.NewRequest(http.MethodPost, "/pairing-sessions", nil)
	req.Header.Set("Authorization", "Bearer "+second.Token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent {
		t.Fatalf("administrator request returned %d", response.Code)
	}
}

func TestAdministratorLoginReconnectsSamePhoneAndProfile(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/auth.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	manager := NewManager(db, time.Minute)
	first, err := manager.BootstrapAdmin(context.Background(), LoginRequest{Password: "correct horse battery staple", DeviceName: "Pixel", UserName: "Fernando"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := manager.LoginAdmin(context.Background(), LoginRequest{Password: "correct horse battery staple", DeviceName: "Pixel", UserName: "Fernando"})
	if err != nil {
		t.Fatal(err)
	}
	if second.DeviceID != first.DeviceID {
		t.Fatalf("reinstall created another device: %s != %s", second.DeviceID, first.DeviceID)
	}
	var users, devices int
	if err := db.DB.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&users); err != nil {
		t.Fatal(err)
	}
	if err := db.DB.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&devices); err != nil {
		t.Fatal(err)
	}
	if users != 1 || devices != 1 {
		t.Fatalf("want one profile/device, got users=%d devices=%d", users, devices)
	}
}
