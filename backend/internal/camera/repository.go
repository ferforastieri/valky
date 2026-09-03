package camera

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
)

type Repository struct {
	store *store.Store
	vault *crypto.Vault
}

func NewRepository(s *store.Store, v *crypto.Vault) *Repository {
	return &Repository{store: s, vault: v}
}

func (r *Repository) Create(ctx context.Context, in CreateInput, caps Capabilities, profile string, services ServiceAddresses) (Camera, error) {
	if in.Name == "" || in.Host == "" || in.Username == "" || in.Password == "" || in.RTSPURI == "" {
		return Camera{}, fmt.Errorf("name, host, username, password and rtspUri are required")
	}
	if in.Port == 0 {
		in.Port = 2020
	}
	user, err := r.vault.EncryptString(in.Username)
	if err != nil {
		return Camera{}, err
	}
	pass, err := r.vault.EncryptString(in.Password)
	if err != nil {
		return Camera{}, err
	}
	rtsp, err := r.vault.EncryptString(in.RTSPURI)
	if err != nil {
		return Camera{}, err
	}
	capJSON, _ := json.Marshal(caps)
	now := time.Now().UTC()
	c := Camera{ID: uuid.NewString(), Name: in.Name, Host: in.Host, Port: in.Port, ProfileToken: profile, Capabilities: caps, Services: services, Enabled: true, CreatedAt: now, UpdatedAt: now}
	_, err = r.store.DB.ExecContext(ctx, `INSERT INTO cameras(id,name,host,port,username_enc,password_enc,rtsp_uri_enc,profile_token,capabilities_json,media_xaddr,events_xaddr,ptz_xaddr,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		c.ID, c.Name, c.Host, c.Port, user, pass, rtsp, c.ProfileToken, string(capJSON), services.Media, services.Events, services.PTZ, 1, now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	return c, err
}

func (r *Repository) List(ctx context.Context) ([]Camera, error) {
	rows, err := r.store.DB.QueryContext(ctx, `SELECT id,name,host,port,profile_token,capabilities_json,media_xaddr,events_xaddr,ptz_xaddr,enabled,created_at,updated_at FROM cameras ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Camera, 0)
	for rows.Next() {
		c, err := scanCamera(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (r *Repository) Get(ctx context.Context, id string) (Camera, Credentials, error) {
	row := r.store.DB.QueryRowContext(ctx, `SELECT id,name,host,port,profile_token,capabilities_json,media_xaddr,events_xaddr,ptz_xaddr,enabled,created_at,updated_at,username_enc,password_enc,rtsp_uri_enc FROM cameras WHERE id=?`, id)
	var c Camera
	var caps string
	var enabled int
	var created, updated string
	var user, pass, rtsp []byte
	err := row.Scan(&c.ID, &c.Name, &c.Host, &c.Port, &c.ProfileToken, &caps, &c.Services.Media, &c.Services.Events, &c.Services.PTZ, &enabled, &created, &updated, &user, &pass, &rtsp)
	if err != nil {
		return c, Credentials{}, err
	}
	_ = json.Unmarshal([]byte(caps), &c.Capabilities)
	c.Enabled = enabled == 1
	c.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	c.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	cred := Credentials{}
	cred.Username, err = r.vault.DecryptString(user)
	if err != nil {
		return c, cred, err
	}
	cred.Password, err = r.vault.DecryptString(pass)
	if err != nil {
		return c, cred, err
	}
	cred.RTSPURI, err = r.vault.DecryptString(rtsp)
	return c, cred, err
}

func (r *Repository) Delete(ctx context.Context, id string) error {
	result, err := r.store.DB.ExecContext(ctx, `DELETE FROM cameras WHERE id=?`, id)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

type scanner interface{ Scan(...any) error }

func scanCamera(s scanner) (Camera, error) {
	var c Camera
	var caps, created, updated string
	var enabled int
	err := s.Scan(&c.ID, &c.Name, &c.Host, &c.Port, &c.ProfileToken, &caps, &c.Services.Media, &c.Services.Events, &c.Services.PTZ, &enabled, &created, &updated)
	if err != nil {
		return c, err
	}
	_ = json.Unmarshal([]byte(caps), &c.Capabilities)
	c.Enabled = enabled == 1
	c.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	c.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	return c, nil
}
