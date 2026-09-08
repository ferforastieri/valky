package auth

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPersonalAccountsPairOnceAndReconnectWithoutNewProfile(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/accounts.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := NewManager(db, time.Minute)
	ctx := context.Background()
	_, err = m.BootstrapAdmin(ctx, LoginRequest{Username: "admin", Password: "admin password 123", DeviceName: "Admin"})
	if err != nil {
		t.Fatal(err)
	}
	invite, _ := m.CreatePairing(ctx)
	member, err := m.Pair(ctx, PairRequest{Code: invite.Code, Username: "miriam", Password: "member password 123", DeviceName: "Phone", UserName: "Miriam"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := m.Login(ctx, LoginRequest{Username: " MIRIAM ", Password: "member password 123", DeviceName: "Tablet", UserName: "Someone else", BrowserAdmin: false})
	if err != nil {
		t.Fatal(err)
	}
	var firstID, secondID string
	db.DB.QueryRow("SELECT user_id FROM devices WHERE id=?", member.DeviceID).Scan(&firstID)
	db.DB.QueryRow("SELECT user_id FROM devices WHERE id=?", second.DeviceID).Scan(&secondID)
	if firstID == "" || firstID != secondID {
		t.Fatal("login created another profile")
	}
	browser, err := m.Login(ctx, LoginRequest{Username: "miriam", Password: "member password 123", DeviceName: "Browser", BrowserAdmin: true})
	if err != nil || browser.Admin {
		t.Fatalf("client forged admin: %v", err)
	}
	nextInvite, _ := m.CreatePairing(ctx)
	if _, err = m.Pair(ctx, PairRequest{Code: nextInvite.Code, Username: "MIRIAM", Password: "member password 123", DeviceName: "Phone"}); err == nil {
		t.Fatal("duplicate username registered")
	}
	var count int
	db.DB.QueryRow("SELECT count(*) FROM users").Scan(&count)
	if count != 2 {
		t.Fatalf("unexpected profiles: %d", count)
	}
	var used bool
	db.DB.QueryRow("SELECT used_at IS NOT NULL FROM pairing_sessions WHERE id=?", nextInvite.ID).Scan(&used)
	if used {
		t.Fatal("failed registration consumed invite")
	}
	// A case-insensitive collision cannot alter the existing account.
	if _, err = m.Login(ctx, LoginRequest{Username: "miriam", Password: "wrong", DeviceName: "Phone"}); err == nil {
		t.Fatal("wrong password accepted")
	}
	if _, err = m.Pair(ctx, PairRequest{Code: invite.Code, Username: "new-member", Password: "member password 123", DeviceName: "Phone"}); err == nil {
		t.Fatal("invite reused")
	}
	// Permissions and revocation are effective on already issued browser/native sessions.
	users, _ := m.Users(ctx)
	var u ManagedUser
	for _, item := range users {
		if item.ID == firstID {
			u = item
		}
	}
	for _, mode := range []struct{ view, edit bool }{{false, false}, {true, false}, {true, true}, {false, false}} {
		u.ViewRules = mode.view
		u.EditRules = mode.edit
		if err = m.ManageUser(ctx, u.ID, u, false); err != nil {
			t.Fatal(err)
		}
		for _, session := range []PairResponse{member, browser} {
			for _, edit := range []bool{false, true} {
				h := m.Middleware(m.RequireRules(edit, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })))
				req := httptest.NewRequest("GET", "/rules", nil)
				req.Header.Set("Authorization", "Bearer "+session.Token)
				req.Header.Set("X-Is-Admin", "true")
				res := httptest.NewRecorder()
				h.ServeHTTP(res, req)
				want := 403
				if mode.view && (!edit || mode.edit) {
					want = 204
				}
				if res.Code != want {
					t.Fatalf("view=%v edit=%v operationEdit=%v got=%d want=%d", mode.view, mode.edit, edit, res.Code, want)
				}
			}
		}
	}
	u.Enabled = false
	if err = m.ManageUser(ctx, u.ID, u, false); err != nil {
		t.Fatal(err)
	}
	if _, err = m.authenticateViewer(ctx, browser.Token); err == nil {
		t.Fatal("disabled browser accepted")
	}
	if _, err = m.Authenticate(ctx, member.Token); err == nil {
		t.Fatal("disabled phone accepted")
	}
}

func TestLegacyCredentialsKeepProfileAndRevokeOtherSessions(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/legacy.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	m := NewManager(db, time.Minute)
	ctx := context.Background()
	device, err := m.insertDevice(ctx, "Phone", "Legacy", "pt-BR", true)
	if err != nil {
		t.Fatal(err)
	}
	var id string
	db.DB.QueryRow("SELECT user_id FROM devices WHERE id=?", device.DeviceID).Scan(&id)
	ctx = context.WithValue(context.WithValue(ctx, accountKey, id), deviceKey, device.DeviceID)
	if err = m.SaveCredentials(ctx, CredentialsRequest{Username: "legacy", NewPassword: "new password 123"}); err != nil {
		t.Fatal(err)
	}
	if _, err = m.Authenticate(ctx, device.Token); err != nil {
		t.Fatal("current session lost")
	}
	browser, err := m.Login(ctx, LoginRequest{Username: "legacy", Password: "new password 123", DeviceName: "Browser", BrowserAdmin: true})
	if err != nil {
		t.Fatal(err)
	}
	if err = m.SaveCredentials(ctx, CredentialsRequest{Username: "legacy", NewPassword: "changed password 123"}); err == nil {
		t.Fatal("current password not required after setup")
	}
	if err = m.SaveCredentials(ctx, CredentialsRequest{Username: "legacy", CurrentPassword: "new password 123", NewPassword: "changed password 123"}); err != nil {
		t.Fatal(err)
	}
	if _, err = m.authenticateViewer(ctx, browser.Token); err == nil {
		t.Fatal("old browser survived password change")
	}
	var hash string
	db.DB.QueryRow("SELECT password_hash FROM users WHERE id=?", id).Scan(&hash)
	if !strings.HasPrefix(hash, "$2") {
		t.Fatal("password not hashed")
	}
	var count int
	db.DB.QueryRow("SELECT count(*) FROM users").Scan(&count)
	if count != 1 {
		t.Fatal("migration duplicated user")
	}
}
