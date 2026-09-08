package auth

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestManagedUserRevocationAndLastAdmin(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/users.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := NewManager(db, time.Minute)
	ctx := context.Background()
	a, err := m.insertDevice(ctx, "admin", "Admin", "pt-BR", true)
	if err != nil {
		t.Fatal(err)
	}
	b, err := m.insertDevice(ctx, "phone", "Member", "pt-BR", false)
	if err != nil {
		t.Fatal(err)
	}
	users, err := m.Users(ctx)
	if err != nil || len(users) != 2 {
		t.Fatalf("%v %v", users, err)
	}
	var admin, member ManagedUser
	for _, u := range users {
		if u.Admin {
			admin = u
		} else {
			member = u
		}
	}
	if err := m.ManageUser(ctx, admin.ID, ManagedUser{Name: admin.Name}, false); err == nil {
		t.Fatal("last admin demoted")
	}
	if err := m.ManageUser(ctx, admin.ID, admin, true); err == nil {
		t.Fatal("last admin deleted")
	}
	member.Enabled = false
	if err := m.ManageUser(ctx, member.ID, member, false); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Authenticate(ctx, b.Token); err == nil {
		t.Fatal("disabled user still authenticated")
	}
	if _, err := m.Authenticate(ctx, a.Token); err != nil {
		t.Fatal(err)
	}
	member.Enabled = true
	member.Admin = true
	if err := m.ManageUser(ctx, member.ID, member, false); err != nil {
		t.Fatal(err)
	}
	if _, isAdmin, err := m.authenticate(ctx, b.Token); err != nil || !isAdmin {
		t.Fatalf("role not loaded from database: %v", err)
	}
	if err := m.ManageUser(ctx, member.ID, member, true); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Authenticate(ctx, b.Token); err == nil {
		t.Fatal("deleted token accepted")
	}
}
func TestBrowserAdminCannotBeForged(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/browser.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := NewManager(db, time.Minute)
	for _, admin := range []bool{false, true} {
		session, err := testBrowser(t, m, admin)
		if err != nil {
			t.Fatal(err)
		}
		handler := m.Middleware(m.RequireAdmin(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })))
		for _, cross := range []bool{false, true} {
			req := httptest.NewRequest("PUT", "https://home.test/admin/users/user", nil)
			req.AddCookie(&http.Cookie{Name: ViewerCookie, Value: session.Token})
			req.Header.Set("X-Valkyris-Viewer", "1")
			req.Header.Set("X-Is-Admin", "true")
			if cross {
				req.Header.Set("Origin", "https://evil.test")
			}
			res := httptest.NewRecorder()
			handler.ServeHTTP(res, req)
			want := 403
			if admin && !cross {
				want = 204
			}
			if res.Code != want {
				t.Fatalf("admin=%v cross=%v got=%d want=%d", admin, cross, res.Code, want)
			}
		}
	}
}

func testBrowser(t *testing.T, m *Manager, admin bool) (PairResponse, error) {
	t.Helper()
	device, err := m.insertDevice(context.Background(), "browser fixture", "Browser user", "pt-BR", admin)
	if err != nil {
		return PairResponse{}, err
	}
	var id string
	if err = m.store.DB.QueryRow(`SELECT user_id FROM devices WHERE id=?`, device.DeviceID).Scan(&id); err != nil {
		return PairResponse{}, err
	}
	return m.issueBrowserForUser(context.Background(), id, admin)
}
