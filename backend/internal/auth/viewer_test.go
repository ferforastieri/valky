package auth

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestViewerLoginIsIsolatedAndReadOnly(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/viewer.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := NewManager(db, time.Minute)
	ctx := context.Background()
	mobile, err := m.BootstrapAdmin(ctx, LoginRequest{Password: "password", DeviceName: "Phone"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = m.LoginAdmin(ctx, LoginRequest{Password: "wrong", DeviceName: "Browser", ReadOnly: true}); err == nil {
		t.Fatal("wrong password accepted")
	}
	viewer, err := m.LoginAdmin(ctx, LoginRequest{Password: "password", DeviceName: "Browser", ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	if !viewer.ReadOnly || viewer.Admin {
		t.Fatal("unexpected viewer permissions")
	}
	for _, table := range []string{"users", "devices"} {
		var n int
		if err := db.DB.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&n); err != nil || n != 1 {
			t.Fatalf("viewer created %s: %d %v", table, n, err)
		}
	}
	if _, err = m.Authenticate(ctx, mobile.Token); err != nil {
		t.Fatal("viewer invalidated mobile token")
	}
	h := m.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if IsAdmin(r.Context()) {
			t.Error("viewer is admin")
		}
		w.WriteHeader(204)
	}))
	for _, tc := range []struct {
		method, path string
		status       int
	}{
		{"GET", "/cameras", 204}, {"GET", "/users", 204}, {"GET", "/events", 204},
		{"POST", "/cameras/a/live/webrtc/whep", 204}, {"PATCH", "/cameras/a/live/webrtc/whep/session", 204}, {"DELETE", "/cameras/a/live/webrtc/whep/session", 204},
		{"POST", "/cameras", 403}, {"PUT", "/rules/a", 403}, {"POST", "/me/location", 403}, {"POST", "/events/a/acknowledge", 403}, {"POST", "/system/update", 403}, {"DELETE", "/users/a", 403}, {"POST", "/cameras/a/ptz", 403},
	} {
		r := httptest.NewRequest(tc.method, tc.path, nil)
		r.Header.Set("Authorization", "Bearer "+viewer.Token)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Errorf("%s %s: %d", tc.method, tc.path, w.Code)
		}
	}
	r := httptest.NewRequest("DELETE", "/viewer-session", nil)
	r.Header.Set("Authorization", "Bearer "+viewer.Token)
	w := httptest.NewRecorder()
	m.Middleware(http.HandlerFunc(m.EndViewerSession)).ServeHTTP(w, r)
	if w.Code != 204 {
		t.Fatal(w.Code)
	}
	if _, err = m.authenticateViewer(ctx, viewer.Token); err == nil {
		t.Fatal("logged out token accepted")
	}
	viewer, err = m.issueViewer(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.Exec(`UPDATE viewer_sessions SET expires_at=?`, time.Now().Add(-time.Second).Format(time.RFC3339Nano)); err != nil {
		t.Fatal(err)
	}
	if _, err = m.authenticateViewer(ctx, viewer.Token); err == nil {
		t.Fatal("expired viewer accepted")
	}
}
