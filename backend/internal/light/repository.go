package light

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"

	appcrypto "github.com/ferforastieri/valky/backend/internal/crypto"
	"github.com/ferforastieri/valky/backend/internal/store"
)

// Repository intentionally keeps reading the existing lights table so an
// adapter migration never destroys a user's records. The local protocol
// details (device id, sealed key, address and data point layout) never leave
// the database through the public lighting model.
type Repository struct {
	store *store.Store
	vault *appcrypto.Vault
}

func NewRepository(s *store.Store, v *appcrypto.Vault) *Repository {
	return &Repository{store: s, vault: v}
}

const lightColumns = `id,name,capabilities_json,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at,favorite,dp_mapping_json,protocol_version,device_id`

func (r *Repository) List(ctx context.Context) ([]Light, error) {
	rows, err := r.store.DB.QueryContext(ctx, `SELECT `+lightColumns+` FROM lights ORDER BY name COLLATE NOCASE`)
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
	row := r.store.DB.QueryRowContext(ctx, `SELECT `+lightColumns+`,local_key_enc,last_ip FROM lights WHERE id=?`, id)
	return scanLightWithCredentials(row, r.vault)
}

func (r *Repository) Create(ctx context.Context, item Light, credentials Credentials) (Light, error) {
	sealed, err := r.vault.EncryptString(credentials.Secret)
	if err != nil {
		return Light{}, err
	}
	now := time.Now().UTC()
	_, err = r.store.DB.ExecContext(ctx, `INSERT INTO lights(id,name,device_id,local_key_enc,protocol_version,last_ip,capabilities_json,dp_mapping_json,setup_status,setup_error,enabled,favorite,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		item.ID, item.Name, item.DeviceID, sealed, item.ProtocolVersion, credentials.Address,
		mustJSON(item.Capabilities), item.DPMapping, item.SetupStatus, item.SetupError, boolToInt(item.Enabled), boolToInt(item.Favorite),
		now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	if err != nil {
		return Light{}, err
	}
	created, _, err := r.Get(ctx, item.ID)
	return created, err
}

// Update patches the mutable fields of a light. A nil field keeps its current
// value; the local credentials stay untouched.
func (r *Repository) Update(ctx context.Context, id string, in UpdateInput) (Light, error) {
	item, _, err := r.Get(ctx, id)
	if err != nil {
		return Light{}, err
	}
	if in.Name != nil {
		item.Name = *in.Name
	}
	if in.Enabled != nil {
		item.Enabled = *in.Enabled
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = r.store.DB.ExecContext(ctx, `UPDATE lights SET name=?,enabled=?,updated_at=? WHERE id=?`,
		item.Name, boolToInt(item.Enabled), now, id)
	if err != nil {
		return Light{}, err
	}
	updated, _, err := r.Get(ctx, id)
	return updated, err
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
	var capsJSON, created, updated, mapping string
	var lastSeen sql.NullString
	var enabled, favorite int
	var version float64
	err := s.Scan(&item.ID, &item.Name, &capsJSON, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated, &favorite, &mapping, &version, &item.DeviceID)
	if err != nil {
		return item, err
	}
	populateLight(&item, capsJSON, enabled, favorite, lastSeen, created, updated, mapping, version)
	return item, nil
}

func scanLightWithCredentials(s rowScanner, vault *appcrypto.Vault) (Light, Credentials, error) {
	var item Light
	var capsJSON, created, updated, mapping string
	var lastSeen sql.NullString
	var enabled, favorite int
	var version float64
	var sealed []byte
	var cred Credentials
	err := s.Scan(&item.ID, &item.Name, &capsJSON, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated, &favorite, &mapping, &version, &item.DeviceID, &sealed, &cred.Address)
	if err != nil {
		return item, cred, err
	}
	populateLight(&item, capsJSON, enabled, favorite, lastSeen, created, updated, mapping, version)
	item.LastAddress = cred.Address
	cred.Secret, err = vault.DecryptString(sealed)
	return item, cred, err
}

func populateLight(item *Light, capsJSON string, enabled, favorite int, lastSeen sql.NullString, created, updated, mapping string, version float64) {
	_ = json.Unmarshal([]byte(capsJSON), &item.Capabilities)
	item.Enabled = enabled == 1
	item.Favorite = favorite == 1
	item.DPMapping = mapping
	item.ProtocolVersion = version
	item.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	item.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	if parsed, err := time.Parse(time.RFC3339Nano, lastSeen.String); lastSeen.Valid && err == nil {
		item.LastSeenAt = &parsed
	}
}

// SetFavorite pins or unpins the light on the overview.
func (r *Repository) SetFavorite(ctx context.Context, id string, favorite bool) error {
	result, err := r.store.DB.ExecContext(ctx, `UPDATE lights SET favorite=? WHERE id=?`, boolToInt(favorite), id)
	if err != nil {
		return err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func boolToInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func mustJSON(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}
