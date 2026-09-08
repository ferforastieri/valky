package auth

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

type contextKey string

const (
	deviceKey contextKey = "deviceID"
	adminKey  contextKey = "isAdmin"
)

type Manager struct {
	store    *store.Store
	lifetime time.Duration
}

type PairingSession struct {
	ID        string    `json:"id"`
	Code      string    `json:"code,omitempty"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type LoginRequest struct {
	BrowserAdmin bool   `json:"browserAdmin,omitempty"`
	ReadOnly     bool   `json:"readOnly,omitempty"`
	Password     string `json:"password"`
	DeviceName   string `json:"deviceName"`
	UserName     string `json:"userName"`
	Locale       string `json:"locale"`
}

type PairRequest struct {
	Code       string `json:"code"`
	DeviceName string `json:"deviceName"`
	UserName   string `json:"userName"`
	Locale     string `json:"locale"`
}

type PairResponse struct {
	ReadOnly bool   `json:"readOnly,omitempty"`
	DeviceID string `json:"deviceId"`
	Token    string `json:"token"`
	Admin    bool   `json:"admin"`
}

func (m *Manager) ChangeAdminPassword(ctx context.Context, currentPassword, newPassword string) error {
	if strings.TrimSpace(newPassword) == "" {
		return fmt.Errorf("password is required")
	}
	var encoded string
	if err := m.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key='admin_password_hash'`).Scan(&encoded); err != nil {
		return err
	}
	if bcrypt.CompareHashAndPassword([]byte(encoded), []byte(currentPassword)) != nil {
		return fmt.Errorf("current password is invalid")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	_, err = m.store.DB.ExecContext(ctx, `UPDATE settings SET value=?,updated_at=? WHERE key='admin_password_hash'`, string(hash), time.Now().UTC().Format(time.RFC3339Nano))
	if err == nil {
		_, err = m.store.DB.ExecContext(ctx, `DELETE FROM viewer_sessions`)
	}
	return err
}

func NewManager(s *store.Store, lifetime time.Duration) *Manager {
	return &Manager{store: s, lifetime: lifetime}
}

func (m *Manager) AdminInitialized(ctx context.Context) (bool, error) {
	var value string
	err := m.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key='admin_password_hash'`).Scan(&value)
	if err == sql.ErrNoRows {
		return false, nil
	}
	return err == nil, err
}

func (m *Manager) BootstrapAdmin(ctx context.Context, req LoginRequest) (PairResponse, error) {
	if strings.TrimSpace(req.Password) == "" {
		return PairResponse{}, fmt.Errorf("password is required")
	}
	if strings.TrimSpace(req.DeviceName) == "" {
		return PairResponse{}, fmt.Errorf("device name is required")
	}
	tx, err := m.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return PairResponse{}, err
	}
	defer tx.Rollback()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	passwordHash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return PairResponse{}, err
	}
	result, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO settings(key,value,updated_at) VALUES('admin_password_hash',?,?)`, string(passwordHash), now)
	if err != nil {
		return PairResponse{}, err
	}
	if affected, _ := result.RowsAffected(); affected != 1 {
		return PairResponse{}, fmt.Errorf("administrator is already configured")
	}
	response, err := issueDevice(ctx, tx, req.DeviceName, req.UserName, req.Locale, true)
	if err != nil {
		return PairResponse{}, err
	}
	if err = tx.Commit(); err != nil {
		return PairResponse{}, err
	}
	return response, nil
}

func (m *Manager) LoginAdmin(ctx context.Context, req LoginRequest) (PairResponse, error) {
	var encoded string
	err := m.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key='admin_password_hash'`).Scan(&encoded)
	if req.Password == "" || req.DeviceName == "" || err != nil ||
		bcrypt.CompareHashAndPassword([]byte(encoded), []byte(req.Password)) != nil {
		return PairResponse{}, fmt.Errorf("invalid credentials")
	}
	if req.ReadOnly || req.BrowserAdmin {
		return m.issueBrowser(ctx, req.BrowserAdmin)
	}
	// A phone can lose its local session during an app reinstall. Reuse the
	// matching administrator device/profile and rotate its token instead of
	// creating a second family member with the same phone and name.
	return m.reconnectAdminDevice(ctx, req.DeviceName, req.UserName, req.Locale)
}

func (m *Manager) reconnectAdminDevice(ctx context.Context, name, userName, locale string) (PairResponse, error) {
	if userName == "" {
		userName = name
	}
	if locale == "" {
		locale = "pt-BR"
	}
	var deviceID string
	err := m.store.DB.QueryRowContext(ctx, `SELECT d.id FROM devices d JOIN users u ON u.id=d.user_id
		WHERE d.enabled=1 AND d.is_admin=1 AND d.name=? AND u.name=?
		ORDER BY d.last_seen_at DESC LIMIT 1`, name, userName).Scan(&deviceID)
	if err == sql.ErrNoRows {
		return m.insertDevice(ctx, name, userName, locale, true)
	}
	if err != nil {
		return PairResponse{}, err
	}
	token, err := appcrypto.RandomToken(32)
	if err != nil {
		return PairResponse{}, err
	}
	_, err = m.store.DB.ExecContext(ctx, `UPDATE devices SET token_hash=?,locale=?,last_seen_at=? WHERE id=?`, appcrypto.Hash(token), locale, time.Now().UTC().Format(time.RFC3339Nano), deviceID)
	if err != nil {
		return PairResponse{}, err
	}
	return PairResponse{DeviceID: deviceID, Token: token, Admin: true}, nil
}

func (m *Manager) CreatePairing(ctx context.Context) (PairingSession, error) {
	codeRaw, err := appcrypto.RandomToken(6)
	if err != nil {
		return PairingSession{}, err
	}
	code := strings.ToUpper(codeRaw[:8])
	now := time.Now().UTC()
	session := PairingSession{ID: uuid.NewString(), Code: code, ExpiresAt: now.Add(m.lifetime)}
	_, err = m.store.DB.ExecContext(ctx, `INSERT INTO pairing_sessions(id,code_hash,expires_at,created_at) VALUES(?,?,?,?)`, session.ID, appcrypto.Hash(code), session.ExpiresAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	return session, err
}

func (m *Manager) Pair(ctx context.Context, req PairRequest) (PairResponse, error) {
	if req.Code == "" || req.DeviceName == "" {
		return PairResponse{}, fmt.Errorf("code and deviceName are required")
	}
	if req.Locale == "" {
		req.Locale = "pt-BR"
	}
	rows, err := m.store.DB.QueryContext(ctx, `SELECT id,code_hash,expires_at FROM pairing_sessions WHERE used_at IS NULL`)
	if err != nil {
		return PairResponse{}, err
	}
	var sessionID string
	for rows.Next() {
		var id, expires string
		var hash []byte
		if err = rows.Scan(&id, &hash, &expires); err != nil {
			rows.Close()
			return PairResponse{}, err
		}
		expiry, _ := time.Parse(time.RFC3339Nano, expires)
		if time.Now().Before(expiry) && subtle.ConstantTimeCompare(hash, appcrypto.Hash(strings.ToUpper(req.Code))) == 1 {
			sessionID = id
			break
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return PairResponse{}, err
	}
	if err = rows.Close(); err != nil {
		return PairResponse{}, err
	}
	if sessionID == "" {
		return PairResponse{}, fmt.Errorf("invalid or expired pairing code")
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	tx, err := m.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return PairResponse{}, err
	}
	defer tx.Rollback()
	response, err := issueDevice(ctx, tx, req.DeviceName, req.UserName, req.Locale, false)
	if err != nil {
		return PairResponse{}, err
	}
	result, err := tx.ExecContext(ctx, `UPDATE pairing_sessions SET used_at=? WHERE id=? AND used_at IS NULL`, now, sessionID)
	if err != nil {
		return PairResponse{}, err
	}
	if affected, _ := result.RowsAffected(); affected != 1 {
		return PairResponse{}, fmt.Errorf("invalid or expired pairing code")
	}
	if err = tx.Commit(); err != nil {
		return PairResponse{}, err
	}
	return response, nil
}

func (m *Manager) insertDevice(ctx context.Context, name, userName, locale string, admin bool) (PairResponse, error) {
	return issueDevice(ctx, m.store.DB, name, userName, locale, admin)
}

type contextExecer interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
}

func issueDevice(ctx context.Context, exec contextExecer, name, userName, locale string, admin bool) (PairResponse, error) {
	if locale == "" {
		locale = "pt-BR"
	}
	if userName == "" {
		userName = name
	}
	token, err := appcrypto.RandomToken(32)
	if err != nil {
		return PairResponse{}, err
	}
	deviceID := uuid.NewString()
	userID := uuid.NewString()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	adminValue := 0
	if admin {
		adminValue = 1
	}
	if _, err = exec.ExecContext(ctx, `INSERT INTO users(id,name,color,enabled,created_at,updated_at) VALUES(?,?,?,1,?,?)`, userID, userName, "#5B5BD6", now, now); err != nil {
		return PairResponse{}, err
	}
	_, err = exec.ExecContext(ctx, `INSERT INTO devices(id,user_id,name,token_hash,is_admin,locale,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?)`, deviceID, userID, name, appcrypto.Hash(token), adminValue, locale, now, now)
	if err != nil {
		return PairResponse{}, err
	}
	return PairResponse{DeviceID: deviceID, Token: token, Admin: admin}, nil
}

func (m *Manager) Authenticate(ctx context.Context, token string) (string, error) {
	id, _, err := m.authenticate(ctx, token)
	return id, err
}

func (m *Manager) authenticate(ctx context.Context, token string) (string, bool, error) {
	if token == "" {
		return "", false, fmt.Errorf("missing token")
	}
	var id string
	var admin int
	err := m.store.DB.QueryRowContext(ctx, `SELECT id,is_admin FROM devices WHERE token_hash=? AND enabled=1 AND (user_id IS NULL OR EXISTS(SELECT 1 FROM users WHERE users.id=devices.user_id AND users.enabled=1))`, appcrypto.Hash(token)).Scan(&id, &admin)
	if err != nil {
		return "", false, err
	}
	_, _ = m.store.DB.ExecContext(ctx, `UPDATE devices SET last_seen_at=? WHERE id=?`, time.Now().UTC().Format(time.RFC3339Nano), id)
	return id, admin == 1, nil
}

func (m *Manager) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		cookieAuth := header == ""
		var id string
		var admin bool
		var err error
		if cookieAuth {
			cookie, cookieErr := r.Cookie(ViewerCookie)
			if cookieErr != nil {
				writeUnauthorized(w)
				return
			}
			token = cookie.Value
			id, err = m.authenticateViewer(r.Context(), token)
		} else {
			if !strings.HasPrefix(header, "Bearer ") {
				writeUnauthorized(w)
				return
			}
			id, admin, err = m.authenticate(r.Context(), token)
		}
		viewer := cookieAuth
		if err != nil && !cookieAuth {
			id, err = m.authenticateViewer(r.Context(), token)
			viewer = err == nil
			admin = false
		}
		if err != nil {
			writeUnauthorized(w)
			return
		}
		if viewer {
			if err := m.store.DB.QueryRowContext(r.Context(), `SELECT is_admin FROM viewer_sessions WHERE id=?`, id).Scan(&admin); err != nil {
				writeUnauthorized(w)
				return
			}
			if !(viewerRequestAllowed(r) || (admin && browserAdminRequestAllowed(r))) || (cookieAuth && !ViewerRequestSafe(r)) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusForbidden)
				json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "Acesso de consulta inválido ou operação não permitida."})
				return
			}
			if cookieAuth && !(r.Method == "DELETE" && r.URL.Path == "/viewer-session") {
				now := time.Now().UTC()
				result, updateErr := m.store.DB.ExecContext(r.Context(), `UPDATE viewer_sessions SET expires_at=? WHERE id=? AND expires_at<?`, now.Add(30*24*time.Hour).Format(time.RFC3339Nano), id, now.Add(29*24*time.Hour).Format(time.RFC3339Nano))
				if updateErr != nil {
					http.Error(w, "Session renewal failed", 500)
					return
				}
				if changed, _ := result.RowsAffected(); changed > 0 {
					SetViewerCookie(w, token)
				}
			}
		}
		w.Header().Set("Cache-Control", "no-store")
		ctx := context.WithValue(r.Context(), deviceKey, id)
		ctx = context.WithValue(ctx, adminKey, admin)
		if r.URL.Path == "/realtime" {
			connectionCtx, cancel := context.WithCancel(ctx)
			defer cancel()
			go func() {
				ticker := time.NewTicker(15 * time.Second)
				defer ticker.Stop()
				for {
					select {
					case <-connectionCtx.Done():
						return
					case <-ticker.C:
						var checkErr error
						if viewer {
							_, checkErr = m.authenticateViewer(connectionCtx, token)
						} else {
							_, _, checkErr = m.authenticate(connectionCtx, token)
						}
						if checkErr != nil {
							cancel()
							return
						}
					}
				}
			}()
			ctx = connectionCtx
		}
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (m *Manager) RequireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !IsAdmin(r.Context()) {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("X-Valkyris-Message", "administrator access required")
			w.Header().Set("X-Valkyris-Success", "false")
			w.WriteHeader(http.StatusForbidden)
			_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "administrator access required", "error": "administrator access required"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func DeviceID(ctx context.Context) string { v, _ := ctx.Value(deviceKey).(string); return v }
func IsAdmin(ctx context.Context) bool    { v, _ := ctx.Value(adminKey).(bool); return v }

func writeUnauthorized(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Valkyris-Message", "authentication required")
	w.Header().Set("X-Valkyris-Success", "false")
	w.WriteHeader(http.StatusUnauthorized)
	_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "authentication required", "error": "authentication required"})
}
