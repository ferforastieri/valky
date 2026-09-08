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
	mobile, err := m.BootstrapAdmin(ctx, LoginRequest{Username: "admin", Password: "password 12345", DeviceName: "Phone"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.Exec(`UPDATE users SET is_admin=0`); err != nil {
		t.Fatal(err)
	}
	if _, err = m.Login(ctx, LoginRequest{Username: "admin", Password: "wrong", DeviceName: "Browser", ReadOnly: true}); err == nil {
		t.Fatal("wrong password accepted")
	}
	viewer, err := m.Login(ctx, LoginRequest{Username: "admin", Password: "password 12345", DeviceName: "Browser", ReadOnly: true})
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
	viewer, err = testBrowser(t, m, false)
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

func TestViewerCookieRenewalCSRFAndLogout(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/cookies.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := NewManager(db, time.Minute)
	v, err := testBrowser(t, m, false)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.DB.Exec(`UPDATE viewer_sessions SET expires_at=? WHERE id=?`, time.Now().Add(time.Hour).Format(time.RFC3339Nano), v.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	h := m.Middleware(http.HandlerFunc(m.ViewerSession))
	request := func(header, site string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", "/viewer-session", nil)
		r.AddCookie(&http.Cookie{Name: ViewerCookie, Value: v.Token})
		r.Header.Set("X-Valkyris-Viewer", header)
		r.Header.Set("Sec-Fetch-Site", site)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if w := request("", ""); w.Code != 403 {
		t.Fatal("missing CSRF header accepted")
	}
	if w := request("1", "cross-site"); w.Code != 403 {
		t.Fatal("cross-site accepted")
	}
	w := request("1", "same-origin")
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	cookies := w.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatal("not renewed")
	}
	c := cookies[0]
	if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteStrictMode || c.Path != "/" || c.MaxAge != 30*24*60*60 {
		t.Fatalf("insecure cookie: %+v", c)
	}
	if len(request("1", "same-origin").Result().Cookies()) != 0 {
		t.Fatal("renewed on every poll")
	}
	r := httptest.NewRequest("DELETE", "/viewer-session", nil)
	r.AddCookie(c)
	r.Header.Set("X-Valkyris-Viewer", "1")
	w = httptest.NewRecorder()
	m.Middleware(http.HandlerFunc(m.EndViewerSession)).ServeHTTP(w, r)
	if w.Code != 204 || w.Result().Cookies()[0].MaxAge != -1 {
		t.Fatal("logout did not clear cookie")
	}
	if w := request("1", "same-origin"); w.Code != 401 {
		t.Fatal("revoked token accepted")
	}
}
