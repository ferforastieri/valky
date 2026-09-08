package auth

import (
	"context"
	"encoding/json"
	"fmt"
	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/google/uuid"
	"net/http"
	"net/url"
	"strings"
	"time"
)

func (m *Manager) issueBrowserForUser(ctx context.Context, userID string, admin bool) (PairResponse, error) {
	token, err := appcrypto.RandomToken(32)
	if err != nil {
		return PairResponse{}, err
	}
	now := time.Now().UTC()
	_, err = m.store.DB.ExecContext(ctx, `DELETE FROM viewer_sessions WHERE expires_at<?`, now.Format(time.RFC3339Nano))
	if err != nil {
		return PairResponse{}, err
	}
	id := uuid.NewString()
	_, err = m.store.DB.ExecContext(ctx, `INSERT INTO viewer_sessions(id,token_hash,expires_at,created_at,user_id) VALUES(?,?,?,?,?)`, id, appcrypto.Hash(token), now.Add(30*24*time.Hour).Format(time.RFC3339Nano), now.Format(time.RFC3339Nano), userID)
	return PairResponse{DeviceID: id, Token: token, ReadOnly: !admin, Admin: admin}, err
}
func (m *Manager) authenticateViewer(ctx context.Context, token string) (string, error) {
	var id, expiry string
	err := m.store.DB.QueryRowContext(ctx, `SELECT v.id,v.expires_at FROM viewer_sessions v JOIN users u ON u.id=v.user_id WHERE v.token_hash=? AND u.enabled=1`, appcrypto.Hash(token)).Scan(&id, &expiry)
	if err != nil {
		return "", err
	}
	at, err := time.Parse(time.RFC3339Nano, expiry)
	if err != nil || !time.Now().Before(at) {
		return "", fmt.Errorf("viewer session expired")
	}
	return id, nil
}

// WHEP negotiates playback; it does not modify camera settings. No other
// non-GET operation (PTZ, acknowledgement, update, detection or location) passes.
func viewerRequestAllowed(r *http.Request) bool {
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		return true
	}
	if r.Method == http.MethodDelete && r.URL.Path == "/viewer-session" {
		return true
	}
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) < 5 || parts[0] != "cameras" || parts[1] == "" || parts[2] != "live" || parts[3] != "webrtc" || parts[4] != "whep" {
		return false
	}
	return (len(parts) == 5 && r.Method == http.MethodPost) || (len(parts) == 6 && parts[5] != "" && (r.Method == http.MethodDelete || r.Method == http.MethodPatch))
}

const ViewerCookie = "__Host-valkyris-viewer"

func SetViewerCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{Name: ViewerCookie, Value: token, Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 30 * 24 * 60 * 60})
	w.Header().Set("Cache-Control", "no-store")
}

func ViewerRequestSafe(r *http.Request) bool {
	if r.Header.Get("X-Valkyris-Viewer") != "1" || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
		return false
	}
	if origin := r.Header.Get("Origin"); origin != "" {
		parsed, err := url.Parse(origin)
		if err != nil || parsed.Host != r.Host || parsed.Scheme != "https" {
			return false
		}
	}
	return true
}

func (m *Manager) ViewerSession(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	permissions, err := m.Permissions(r.Context())
	if err != nil {
		writeUnauthorized(w)
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"success": true, "data": permissions})
}

func (m *Manager) EndViewerSession(w http.ResponseWriter, r *http.Request) {
	_, err := m.store.DB.ExecContext(r.Context(), `DELETE FROM viewer_sessions WHERE id=?`, DeviceID(r.Context()))
	if err != nil {
		http.Error(w, "Unable to end session", http.StatusInternalServerError)
		return
	}
	http.SetCookie(w, &http.Cookie{Name: ViewerCookie, Value: "", Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: -1})
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}

// Browser administration is deliberately limited to user management and rule
// recipients; ordinary viewer sessions retain their existing read-only scope.
func browserAdminRequestAllowed(r *http.Request) bool {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) == 3 && parts[0] == "admin" && parts[1] == "users" && parts[2] != "" {
		return r.Method == "PUT" || r.Method == "DELETE"
	}
	if len(parts) == 3 && parts[0] == "rules" && parts[2] == "recipients" {
		return r.Method == "PUT"
	}
	return r.URL.Path == "/pairing-sessions" && r.Method == "POST"
}

// Mutations that are available to personal browser accounts still pass the
// endpoint's authorization middleware and same-origin cookie checks.
func browserAccountRequestAllowed(r *http.Request) bool {
	if r.URL.Path == "/me/credentials" || r.URL.Path == "/me/password" {
		return r.Method == "POST"
	}
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	return len(parts) == 3 && parts[0] == "rules" && parts[1] != "" && parts[2] == "recipients" && r.Method == "PUT"
}
