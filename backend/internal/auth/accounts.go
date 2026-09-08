package auth

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

const accountKey contextKey = "accountID"

// A valid bcrypt hash keeps unknown-user authentication on the same expensive path.
const dummyPasswordHash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"

var usernamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{2,39}$`)

func normalizeUsername(value string) string { return strings.ToLower(strings.TrimSpace(value)) }
func validateCredentials(username, password string) error {
	if !usernamePattern.MatchString(normalizeUsername(username)) {
		return fmt.Errorf("use 3 to 40 letters, digits, dots, underscores or hyphens for username")
	}
	// bcrypt rejects passwords over 72 bytes rather than silently truncating.
	if utf8.RuneCountInString(password) < 12 || len(password) > 72 {
		return fmt.Errorf("password must contain at least 12 characters and at most 72 bytes")
	}
	return nil
}
func AccountID(ctx context.Context) string { value, _ := ctx.Value(accountKey).(string); return value }

func setCredentials(ctx context.Context, tx *sql.Tx, id, username, password string) error {
	if err := validateCredentials(username, password); err != nil {
		return err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `UPDATE users SET username=?,password_hash=?,updated_at=? WHERE id=?`, normalizeUsername(username), string(hash), time.Now().UTC().Format(time.RFC3339Nano), id)
	if err != nil {
		return fmt.Errorf("username unavailable")
	}
	count, _ := result.RowsAffected()
	if count != 1 {
		return sql.ErrNoRows
	}
	return nil
}

type Permissions struct {
	Admin                 bool   `json:"admin"`
	ReadOnly              bool   `json:"readOnly"`
	ViewRules             bool   `json:"viewRules"`
	EditRules             bool   `json:"editRules"`
	Username              string `json:"username"`
	CredentialsConfigured bool   `json:"credentialsConfigured"`
}

func (m *Manager) Permissions(ctx context.Context) (Permissions, error) {
	var out Permissions
	err := m.store.DB.QueryRowContext(ctx, `SELECT is_admin,view_rules,edit_rules,COALESCE(username,''),password_hash<>'' FROM users WHERE id=? AND enabled=1`, AccountID(ctx)).Scan(&out.Admin, &out.ViewRules, &out.EditRules, &out.Username, &out.CredentialsConfigured)
	out.EditRules = out.Admin || out.EditRules
	out.ViewRules = out.Admin || out.ViewRules || out.EditRules
	out.ReadOnly = !out.Admin && !out.EditRules
	return out, err
}
func (m *Manager) RequireRules(edit bool, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		permissions, err := m.Permissions(r.Context())
		if err != nil || !permissions.ViewRules || (edit && !permissions.EditRules) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "Você não tem permissão para acessar estas regras."})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (m *Manager) accountDevice(ctx context.Context, userID, name, locale string, admin bool) (PairResponse, error) {
	if locale == "" {
		locale = "pt-BR"
	}
	token, err := appcrypto.RandomToken(32)
	if err != nil {
		return PairResponse{}, err
	}
	tx, err := m.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return PairResponse{}, err
	}
	defer tx.Rollback()
	// Reuse an active session for this account and installation; never match a display name.
	var id string
	err = tx.QueryRowContext(ctx, `SELECT id FROM devices WHERE user_id=? AND name=? AND enabled=1 ORDER BY last_seen_at DESC LIMIT 1`, userID, name).Scan(&id)
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if err == sql.ErrNoRows {
		id = uuid.NewString()
		_, err = tx.ExecContext(ctx, `INSERT INTO devices(id,user_id,name,token_hash,is_admin,locale,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?)`, id, userID, name, appcrypto.Hash(token), admin, locale, now, now)
	} else if err == nil {
		_, err = tx.ExecContext(ctx, `UPDATE devices SET token_hash=?,locale=?,last_seen_at=?,is_admin=? WHERE id=?`, appcrypto.Hash(token), locale, now, admin, id)
	}
	if err != nil {
		return PairResponse{}, err
	}
	if err = tx.Commit(); err != nil {
		return PairResponse{}, err
	}
	return PairResponse{DeviceID: id, Token: token, Admin: admin}, nil
}

type CredentialsRequest struct {
	Username        string `json:"username"`
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

func (m *Manager) SaveCredentials(ctx context.Context, in CredentialsRequest) error {
	tx, err := m.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var hash, username string
	err = tx.QueryRowContext(ctx, `SELECT password_hash,COALESCE(username,'') FROM users WHERE id=? AND enabled=1`, AccountID(ctx)).Scan(&hash, &username)
	if err != nil {
		return err
	}
	// Only a still-authenticated legacy device may choose the initial credentials.
	if hash != "" && bcrypt.CompareHashAndPassword([]byte(hash), []byte(in.CurrentPassword)) != nil {
		return fmt.Errorf("current password is invalid")
	}
	if in.Username == "" {
		in.Username = username
	}
	if err = setCredentials(ctx, tx, AccountID(ctx), in.Username, in.NewPassword); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM viewer_sessions WHERE user_id=? AND id<>?`, AccountID(ctx), DeviceID(ctx)); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE devices SET enabled=0 WHERE user_id=? AND id<>?`, AccountID(ctx), DeviceID(ctx)); err != nil {
		return err
	}
	return tx.Commit()
}
