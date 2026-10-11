package remote

import (
	"context"
	"database/sql"
	"strings"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

type Repository struct {
	store *store.Store
	vault *appcrypto.Vault
}

func NewRepository(s *store.Store, v *appcrypto.Vault) *Repository {
	return &Repository{store: s, vault: v}
}

func (r *Repository) List(ctx context.Context) ([]Device, error) {
	rows, err := r.store.DB.QueryContext(ctx, `SELECT id,name,kind,host,port,mac,token_enc,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at FROM remotes ORDER BY name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		item, _, err := scanDevice(rows, nil)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (r *Repository) Get(ctx context.Context, id string) (Device, Credentials, error) {
	row := r.store.DB.QueryRowContext(ctx, `SELECT id,name,kind,host,port,mac,token_enc,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at FROM remotes WHERE id=?`, id)
	return scanDevice(row, r.vault)
}

func (r *Repository) Create(ctx context.Context, item Device, token string) (Device, error) {
	sealed, err := r.seal(token)
	if err != nil {
		return Device{}, err
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = r.store.DB.ExecContext(ctx, `INSERT INTO remotes(id,name,kind,host,port,mac,token_enc,setup_status,setup_error,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`,
		item.ID, item.Name, item.Kind, item.Host, item.Port, item.MAC, sealed, item.SetupStatus, item.SetupError, boolToInt(item.Enabled), now, now)
	if err != nil {
		return Device{}, err
	}
	created, _, err := r.Get(ctx, item.ID)
	return created, err
}

func (r *Repository) Update(ctx context.Context, id string, in UpdateInput) (Device, error) {
	current, _, err := r.Get(ctx, id)
	if err != nil {
		return Device{}, err
	}
	name, host, port, enabled := current.Name, current.Host, current.Port, current.Enabled
	mac := current.MAC
	if in.Name != nil && *in.Name != "" {
		name = *in.Name
	}
	if in.Host != nil && *in.Host != "" {
		host = *in.Host
	}
	if in.Port != nil && *in.Port > 0 {
		port = *in.Port
	}
	if in.MAC != nil {
		mac = strings.TrimSpace(*in.MAC)
	}
	if in.Enabled != nil {
		enabled = *in.Enabled
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = r.store.DB.ExecContext(ctx, `UPDATE remotes SET name=?,host=?,port=?,mac=?,enabled=?,updated_at=? WHERE id=?`, name, host, port, mac, boolToInt(enabled), now, id); err != nil {
		return Device{}, err
	}
	updated, _, err := r.Get(ctx, id)
	return updated, err
}

func (r *Repository) Delete(ctx context.Context, id string) error {
	result, err := r.store.DB.ExecContext(ctx, `DELETE FROM remotes WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

// SetToken stores a freshly issued access token and marks the device paired.
func (r *Repository) SetToken(ctx context.Context, id, token string) error {
	sealed, err := r.seal(token)
	if err != nil {
		return err
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = r.store.DB.ExecContext(ctx, `UPDATE remotes SET token_enc=?,setup_status='ready',setup_error='',last_seen_at=?,updated_at=? WHERE id=?`, sealed, now, now, id)
	return err
}

// SetMAC stores the device network address used for Wake-on-LAN.
func (r *Repository) SetMAC(ctx context.Context, id, mac string) error {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err := r.store.DB.ExecContext(ctx, `UPDATE remotes SET mac=?,updated_at=? WHERE id=?`, mac, now, id)
	return err
}

func (r *Repository) SetStatus(ctx context.Context, id, status, setupError string, seen bool) error {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	var seenAt any
	if seen {
		seenAt = now
	}
	_, err := r.store.DB.ExecContext(ctx, `UPDATE remotes SET setup_status=?,setup_error=?,last_seen_at=COALESCE(?,last_seen_at),updated_at=? WHERE id=?`, status, setupError, seenAt, now, id)
	return err
}

func (r *Repository) seal(token string) ([]byte, error) {
	if token == "" {
		return nil, nil
	}
	return r.vault.EncryptString(token)
}

type rowScanner interface{ Scan(...any) error }

func scanDevice(s rowScanner, vault *appcrypto.Vault) (Device, Credentials, error) {
	var item Device
	var sealed []byte
	var enabled int
	var lastSeen sql.NullString
	var created, updated string
	if err := s.Scan(&item.ID, &item.Name, &item.Kind, &item.Host, &item.Port, &item.MAC, &sealed, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated); err != nil {
		return item, Credentials{}, err
	}
	item.Enabled = enabled == 1
	item.TokenConfigured = len(sealed) > 0
	item.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	item.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	if parsed, err := time.Parse(time.RFC3339Nano, lastSeen.String); lastSeen.Valid && err == nil {
		item.LastSeenAt = &parsed
	}
	credentials := Credentials{Address: item.Host}
	if vault != nil && len(sealed) > 0 {
		token, err := vault.DecryptString(sealed)
		if err != nil {
			return item, credentials, err
		}
		credentials.Token = token
	}
	return item, credentials, nil
}

func boolToInt(value bool) int {
	if value {
		return 1
	}
	return 0
}
