package light

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
)

type Repository struct {
	store *store.Store
	vault *appcrypto.Vault
}

func NewRepository(s *store.Store, v *appcrypto.Vault) *Repository {
	return &Repository{store: s, vault: v}
}

func (r *Repository) Create(ctx context.Context, in CreateInput) (Light, error) {
	in.Name, in.Room, in.DeviceID, in.LocalKey, in.IP = strings.TrimSpace(in.Name), strings.TrimSpace(in.Room), strings.TrimSpace(in.DeviceID), strings.TrimSpace(in.LocalKey), strings.TrimSpace(in.IP)
	if in.Name == "" || in.DeviceID == "" || len(in.LocalKey) != 16 {
		return Light{}, fmt.Errorf("name, deviceId and a 16-character localKey are required")
	}
	if err := validateIP(in.IP); err != nil {
		return Light{}, err
	}
	if in.ProtocolVersion == 0 {
		in.ProtocolVersion = 3.3
	}
	if !validVersion(in.ProtocolVersion) {
		return Light{}, fmt.Errorf("unsupported Tuya protocol version")
	}
	sealed, err := r.vault.EncryptString(in.LocalKey)
	if err != nil {
		return Light{}, err
	}
	now := time.Now().UTC()
	caps := Capabilities{Brightness: true, Color: true, ColorTemperature: true}
	capsJSON, _ := json.Marshal(caps)
	mappingJSON, _ := json.Marshal(DefaultDPMapping())
	item := Light{ID: uuid.NewString(), Name: in.Name, Room: in.Room, DeviceID: in.DeviceID, ProtocolVersion: in.ProtocolVersion, Capabilities: caps, SetupStatus: "pending", Enabled: true, CreatedAt: now, UpdatedAt: now}
	_, err = r.store.DB.ExecContext(ctx, `INSERT INTO lights(id,name,room,device_id,local_key_enc,protocol_version,last_ip,capabilities_json,dp_mapping_json,setup_status,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?)`, item.ID, item.Name, item.Room, item.DeviceID, sealed, item.ProtocolVersion, in.IP, string(capsJSON), string(mappingJSON), item.SetupStatus, now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	return item, err
}

func (r *Repository) List(ctx context.Context) ([]Light, error) {
	rows, err := r.store.DB.QueryContext(ctx, `SELECT id,name,room,device_id,protocol_version,capabilities_json,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at FROM lights ORDER BY room COLLATE NOCASE,name COLLATE NOCASE`)
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
	row := r.store.DB.QueryRowContext(ctx, `SELECT id,name,room,device_id,protocol_version,capabilities_json,setup_status,setup_error,enabled,last_seen_at,created_at,updated_at,local_key_enc,last_ip,dp_mapping_json FROM lights WHERE id=?`, id)
	item, cred, err := scanLightWithCredentials(row, r.vault)
	return item, cred, err
}

func (r *Repository) Update(ctx context.Context, id string, in UpdateInput) (Light, error) {
	current, cred, err := r.Get(ctx, id)
	if err != nil {
		return Light{}, err
	}
	in.Name, in.Room, in.IP = strings.TrimSpace(in.Name), strings.TrimSpace(in.Room), strings.TrimSpace(in.IP)
	if in.Name == "" {
		return Light{}, fmt.Errorf("name is required")
	}
	if in.IP == "" {
		in.IP = cred.LastIP
	}
	if err := validateIP(in.IP); err != nil {
		return Light{}, err
	}
	if in.ProtocolVersion == 0 {
		in.ProtocolVersion = current.ProtocolVersion
	}
	if !validVersion(in.ProtocolVersion) {
		return Light{}, fmt.Errorf("unsupported Tuya protocol version")
	}
	sealed, err := r.vault.EncryptString(cred.LocalKey)
	if err != nil {
		return Light{}, err
	}
	if in.LocalKey != "" {
		if len(strings.TrimSpace(in.LocalKey)) != 16 {
			return Light{}, fmt.Errorf("localKey must contain 16 characters")
		}
		sealed, err = r.vault.EncryptString(strings.TrimSpace(in.LocalKey))
		if err != nil {
			return Light{}, err
		}
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	result, err := r.store.DB.ExecContext(ctx, `UPDATE lights SET name=?,room=?,local_key_enc=?,protocol_version=?,last_ip=?,setup_status='pending',setup_error='',enabled=?,updated_at=? WHERE id=?`, in.Name, in.Room, sealed, in.ProtocolVersion, in.IP, boolInt(in.Enabled), now, id)
	if err != nil {
		return Light{}, err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return Light{}, sql.ErrNoRows
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

func (r *Repository) SetConnection(ctx context.Context, id, ip, status, setupError string, seen bool) error {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	var seenAt any
	if seen {
		seenAt = now
	}
	_, err := r.store.DB.ExecContext(ctx, `UPDATE lights SET last_ip=CASE WHEN ?='' THEN last_ip ELSE ? END,setup_status=?,setup_error=?,last_seen_at=COALESCE(?,last_seen_at),updated_at=? WHERE id=?`, ip, ip, status, setupError, seenAt, now, id)
	return err
}

type rowScanner interface{ Scan(...any) error }

func scanLight(s rowScanner) (Light, error) {
	var item Light
	var capsJSON, created, updated string
	var lastSeen sql.NullString
	var enabled int
	err := s.Scan(&item.ID, &item.Name, &item.Room, &item.DeviceID, &item.ProtocolVersion, &capsJSON, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated)
	if err != nil {
		return item, err
	}
	_ = json.Unmarshal([]byte(capsJSON), &item.Capabilities)
	item.Enabled = enabled == 1
	item.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	item.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	if parsed, err := time.Parse(time.RFC3339Nano, lastSeen.String); lastSeen.Valid && err == nil {
		item.LastSeenAt = &parsed
	}
	return item, nil
}

func scanLightWithCredentials(s rowScanner, vault *appcrypto.Vault) (Light, Credentials, error) {
	var item Light
	var capsJSON, created, updated, mappingJSON string
	var lastSeen sql.NullString
	var enabled int
	var sealed []byte
	var cred Credentials
	err := s.Scan(&item.ID, &item.Name, &item.Room, &item.DeviceID, &item.ProtocolVersion, &capsJSON, &item.SetupStatus, &item.SetupError, &enabled, &lastSeen, &created, &updated, &sealed, &cred.LastIP, &mappingJSON)
	if err != nil {
		return item, cred, err
	}
	_ = json.Unmarshal([]byte(capsJSON), &item.Capabilities)
	_ = json.Unmarshal([]byte(mappingJSON), &cred.Mapping)
	if cred.Mapping.Power == 0 {
		cred.Mapping = DefaultDPMapping()
	}
	item.Enabled = enabled == 1
	item.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	item.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	if parsed, err := time.Parse(time.RFC3339Nano, lastSeen.String); lastSeen.Valid && err == nil {
		item.LastSeenAt = &parsed
	}
	cred.LocalKey, err = vault.DecryptString(sealed)
	return item, cred, err
}

func validateIP(value string) error {
	if value == "" {
		return nil
	}
	ip := net.ParseIP(value)
	if ip == nil || ip.To4() == nil || !(ip.IsPrivate() || ip.IsLoopback()) {
		return fmt.Errorf("ip must be a private IPv4 network address")
	}
	return nil
}
func validVersion(v float64) bool { return v == 3.1 || v == 3.2 || v == 3.3 || v == 3.4 || v == 3.5 }
func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
