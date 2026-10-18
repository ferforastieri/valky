package auth

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	appcrypto "github.com/ferforastieri/valky/backend/internal/crypto"
	"github.com/ferforastieri/valky/backend/internal/store"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

type contextKey string

const (
	deviceKey contextKey = "deviceID"
	adminKey  contextKey = "isAdmin"
	viewerKey contextKey = "isViewer"
)

type Manager struct {
	store    *store.Store
	lifetime time.Duration
	// lastSeen evita um UPDATE por requisição autenticada: o banco abre uma
	// única conexão e o heartbeat competia com o caminho de dados.
	touchMu  sync.Mutex
	lastSeen map[string]time.Time
}

type PairingSession struct {
	ID        string    `json:"id"`
	Code      string    `json:"code,omitempty"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type LoginRequest struct {
	Username     string `json:"username"`
	BrowserAdmin bool   `json:"browserAdmin,omitempty"`
	ReadOnly     bool   `json:"readOnly,omitempty"`
	Password     string `json:"password"`
	DeviceName   string `json:"deviceName"`
	UserName     string `json:"userName"`
	Locale       string `json:"locale"`
	// SetupToken é o código de instalação impresso pelo instalador; só é
	// exigido na criação do primeiro administrador.
	SetupToken string `json:"setupToken,omitempty"`
}

type PairRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
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

func NewManager(s *store.Store, lifetime time.Duration) *Manager {
	return &Manager{store: s, lifetime: lifetime, lastSeen: map[string]time.Time{}}
}

// lastSeenInterval é o intervalo mínimo entre gravações de last_seen_at.
const lastSeenInterval = 5 * time.Minute

// touchDevice grava o heartbeat no máximo uma vez a cada lastSeenInterval.
func (m *Manager) touchDevice(id string) {
	now := time.Now()
	m.touchMu.Lock()
	previous, ok := m.lastSeen[id]
	if ok && now.Sub(previous) < lastSeenInterval {
		m.touchMu.Unlock()
		return
	}
	m.lastSeen[id] = now
	if len(m.lastSeen) > 4096 {
		m.lastSeen = map[string]time.Time{id: now}
	}
	m.touchMu.Unlock()
	_, _ = m.store.DB.ExecContext(context.Background(), `UPDATE devices SET last_seen_at=? WHERE id=?`, now.UTC().Format(time.RFC3339Nano), id)
}

func (m *Manager) AdminInitialized(ctx context.Context) (bool, error) {
	var value string
	err := m.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key IN ('admin_initialized','admin_password_hash')`).Scan(&value)
	if err == sql.ErrNoRows {
		return false, nil
	}
	return err == nil, err
}

func (m *Manager) BootstrapAdmin(ctx context.Context, req LoginRequest) (PairResponse, error) {
	if err := validateCredentials(req.Username, req.Password); err != nil {
		return PairResponse{}, err
	}
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
	var initialized int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM settings WHERE key IN ('admin_initialized','admin_password_hash')`).Scan(&initialized); err != nil {
		return PairResponse{}, err
	}
	if initialized > 0 {
		return PairResponse{}, fmt.Errorf("administrator is already configured")
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	passwordHash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return PairResponse{}, err
	}
	result, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO settings(key,value,updated_at) VALUES('admin_initialized',?,?)`, "true", now)
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
	if _, err = tx.ExecContext(ctx, `UPDATE users SET username=?,password_hash=?,is_admin=1 WHERE id=(SELECT user_id FROM devices WHERE id=?)`, normalizeUsername(req.Username), string(passwordHash), response.DeviceID); err != nil {
		return PairResponse{}, err
	}
	if err = tx.Commit(); err != nil {
		return PairResponse{}, err
	}
	return response, nil
}

func (m *Manager) Login(ctx context.Context, req LoginRequest) (PairResponse, error) {
	var id, encoded string
	var admin bool
	err := m.store.DB.QueryRowContext(ctx, `SELECT id,password_hash,is_admin FROM users WHERE username=? AND enabled=1`, normalizeUsername(req.Username)).Scan(&id, &encoded, &admin)
	if err != nil {
		encoded = dummyPasswordHash
	}
	valid := bcrypt.CompareHashAndPassword([]byte(encoded), []byte(req.Password)) == nil
	if err != nil || !valid || req.Username == "" || req.DeviceName == "" {
		return PairResponse{}, fmt.Errorf("invalid credentials")
	}
	if req.ReadOnly || req.BrowserAdmin {
		return m.issueBrowserForUser(ctx, id, admin)
	}
	return m.accountDevice(ctx, id, req.DeviceName, req.Locale, admin)
}

func (m *Manager) CreatePairing(ctx context.Context) (PairingSession, error) {
	// 12 bytes viram 16 caracteres: o convite é lido por QR Code, então o
	// tamanho extra não custa nada e tira o código do alcance de uma busca
	// offline caso o banco vaze.
	codeRaw, err := appcrypto.RandomToken(12)
	if err != nil {
		return PairingSession{}, err
	}
	code := strings.ToUpper(codeRaw[:16])
	now := time.Now().UTC()
	session := PairingSession{ID: uuid.NewString(), Code: code, ExpiresAt: now.Add(m.lifetime)}
	_, err = m.store.DB.ExecContext(ctx, `INSERT INTO pairing_sessions(id,code_hash,expires_at,created_at) VALUES(?,?,?,?)`, session.ID, appcrypto.Hash(code), session.ExpiresAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	return session, err
}

func (m *Manager) Pair(ctx context.Context, req PairRequest) (PairResponse, error) {
	if err := validateCredentials(req.Username, req.Password); err != nil {
		return PairResponse{}, err
	}
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

	passwordHash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return PairResponse{}, err
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
	if _, err = tx.ExecContext(ctx, `UPDATE users SET username=?,password_hash=? WHERE id=(SELECT user_id FROM devices WHERE id=?)`, normalizeUsername(req.Username), string(passwordHash), response.DeviceID); err != nil {
		return PairResponse{}, fmt.Errorf("username unavailable; sign in if you already have an account")
	}
	result, err := tx.ExecContext(ctx, `UPDATE pairing_sessions SET used_at=? WHERE id=? AND used_at IS NULL AND julianday(expires_at)>julianday(?)`, now, sessionID, now)
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
	if _, err = exec.ExecContext(ctx, `INSERT INTO users(id,name,color,enabled,is_admin,created_at,updated_at) VALUES(?,?,?,1,?,?,?)`, userID, userName, "#5B5BD6", adminValue, now, now); err != nil {
		return PairResponse{}, err
	}
	_, err = exec.ExecContext(ctx, `INSERT INTO devices(id,user_id,name,token_hash,is_admin,locale,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?)`, deviceID, userID, name, appcrypto.Hash(token), adminValue, locale, now, now)
	if err != nil {
		return PairResponse{}, err
	}
	return PairResponse{DeviceID: deviceID, Token: token, Admin: admin}, nil
}

func (m *Manager) Authenticate(ctx context.Context, token string) (string, error) {
	id, _, _, err := m.authenticate(ctx, token)
	return id, err
}

// authenticate resolve o dispositivo e já devolve o dono e o papel, para o
// middleware não precisar de mais nenhuma consulta.
func (m *Manager) authenticate(ctx context.Context, token string) (string, bool, string, error) {
	if token == "" {
		return "", false, "", fmt.Errorf("missing token")
	}
	var id, userID string
	var admin int
	err := m.store.DB.QueryRowContext(ctx, `SELECT d.id,d.user_id,u.is_admin FROM devices d JOIN users u ON u.id=d.user_id WHERE d.token_hash=? AND d.enabled=1 AND u.enabled=1`, appcrypto.Hash(token)).Scan(&id, &userID, &admin)
	if err != nil {
		return "", false, "", err
	}
	m.touchDevice(id)
	return id, admin == 1, userID, nil
}

func (m *Manager) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		cookieAuth := header == ""
		var id, accountID string
		var admin bool
		var err error
		if cookieAuth {
			cookie, cookieErr := r.Cookie(ViewerCookie)
			if cookieErr != nil {
				writeUnauthorized(w)
				return
			}
			token = cookie.Value
			id, accountID, admin, err = m.authenticateViewer(r.Context(), token)
		} else {
			if !strings.HasPrefix(header, "Bearer ") {
				writeUnauthorized(w)
				return
			}
			id, admin, accountID, err = m.authenticate(r.Context(), token)
		}
		viewer := cookieAuth
		if err != nil && !cookieAuth {
			id, accountID, admin, err = m.authenticateViewer(r.Context(), token)
			viewer = err == nil
			admin = false
		}
		if err != nil {
			writeUnauthorized(w)
			return
		}
		if viewer {
			if !(viewerRequestAllowed(r) || browserAccountRequestAllowed(r) || (admin && browserAdminRequestAllowed(r))) || (cookieAuth && !ViewerRequestSafe(r)) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusForbidden)
				json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "Acesso de consulta inválido ou operação não permitida."})
				return
			}
			if cookieAuth && !(r.Method == "DELETE" && r.URL.Path == "/viewer-session") {
				now := time.Now().UTC()
				// Renova só quando faltam menos de cinco dias: com o limite em
				// 29 dias a escrita acontecia em praticamente todo request.
				result, updateErr := m.store.DB.ExecContext(r.Context(), `UPDATE viewer_sessions SET expires_at=? WHERE id=? AND expires_at<?`, now.Add(30*24*time.Hour).Format(time.RFC3339Nano), id, now.Add(25*24*time.Hour).Format(time.RFC3339Nano))
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
		ctx = context.WithValue(ctx, viewerKey, viewer)
		ctx = context.WithValue(ctx, accountKey, accountID)
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
							_, _, _, checkErr = m.authenticateViewer(connectionCtx, token)
						} else {
							_, _, _, checkErr = m.authenticate(connectionCtx, token)
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
			w.Header().Set("X-Valky-Message", "administrator access required")
			w.Header().Set("X-Valky-Success", "false")
			w.WriteHeader(http.StatusForbidden)
			_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "administrator access required", "error": "administrator access required"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func DeviceID(ctx context.Context) string { v, _ := ctx.Value(deviceKey).(string); return v }
func IsAdmin(ctx context.Context) bool    { v, _ := ctx.Value(adminKey).(bool); return v }

// IsViewerSession distingue o navegador (cookie de consulta) do aplicativo
// pareado. Operações de administração pedem uma prova extra nesse caso.
func IsViewerSession(ctx context.Context) bool { v, _ := ctx.Value(viewerKey).(bool); return v }

func writeUnauthorized(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Valky-Message", "authentication required")
	w.Header().Set("X-Valky-Success", "false")
	w.WriteHeader(http.StatusUnauthorized)
	_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "authentication required", "error": "authentication required"})
}
