package light

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

// Repository intentionally keeps reading the existing lights table so an
// adapter migration never destroys a user's records. Legacy provider columns
// are storage details and are no longer exposed by the public lighting model.
type Repository struct {
	store *store.Store
	vault *appcrypto.Vault
}

func NewRepository(s *store.Store, v *appcrypto.Vault) *Repository {
	return &Repository{store: s, vault: v}
}

func (r *Repository) List(ctx context.Context) ([]Light, error) {
	rows, err := r.store.DB.QueryContext(ctx, `SELECT id,name,room,capabilities_json,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at FROM lights ORDER BY room COLLATE NOCASE,name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Light{}
	for rows.Next() {
		item, err := scanLight(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (r *Repository) Get(ctx context.Context, id string) (Light, Credentials, error) {
	row := r.store.DB.QueryRowContext(ctx, `SELECT id,name,room,capabilities_json,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at,local_key_enc,last_ip FROM lights WHERE id=?`, id)
	return scanLightWithCredentials(row, r.vault)
}

func (r *Repository) Delete(ctx context.Context, id string) error {
	result, err := r.store.DB.ExecContext(ctx, `DELETE FROM lights WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func (r *Repository) SetConnection(ctx context.Context, id, address, status, setupError string, seen bool) error {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	var seenAt any
	if seen {
		seenAt = now
	}
	_, err := r.store.DB.ExecContext(ctx, `UPDATE lights SET last_ip=CASE WHEN ?='' THEN last_ip ELSE ? END,setup_status=?,setup_error=?,last_seen_at=COALESCE(?,last_seen_at),updated_at=? WHERE id=?`, address, address, status, setupError, seenAt, now, id)
	return err
}

type rowScanner interface{ Scan(...any) error }

func scanLight(s rowScanner) (Light, error) {
	var item Light
	var capsJSON, created, updated string
	var lastSeen sql.NullString
	var enabled int
	err := s.Scan(&item.ID, &item.Name, &item.Room, &capsJSON, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated)
	if err != nil {
		return item, err
	}
	populateLight(&item, capsJSON, enabled, lastSeen, created, updated)
	return item, nil
}

func scanLightWithCredentials(s rowScanner, vault *appcrypto.Vault) (Light, Credentials, error) {
	var item Light
	var capsJSON, created, updated string
	var lastSeen sql.NullString
	var enabled int
	var sealed []byte
	var cred Credentials
	err := s.Scan(&item.ID, &item.Name, &item.Room, &capsJSON, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated, &sealed, &cred.Address)
	if err != nil {
		return item, cred, err
	}
	populateLight(&item, capsJSON, enabled, lastSeen, created, updated)
	cred.Secret, err = vault.DecryptString(sealed)
	return item, cred, err
}

func populateLight(item *Light, capsJSON string, enabled int, lastSeen sql.NullString, created, updated string) {
	_ = json.Unmarshal([]byte(capsJSON), &item.Capabilities)
	item.Enabled = enabled == 1
	item.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	item.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	if parsed, err := time.Parse(time.RFC3339Nano, lastSeen.String); lastSeen.Valid && err == nil {
		item.LastSeenAt = &parsed
	}
}
