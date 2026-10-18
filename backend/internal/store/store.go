package store

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
)

//go:embed schema.sql
var schema string

type Store struct{ DB *sql.DB }

// adotaBancoAntigo evita que uma mudança de nome/endereço do banco pareça
// perda de dados: se o arquivo configurado não existe e há um banco antigo do
// projeto na mesma pasta, ele é adotado (renomeado) em vez de criar um vazio.
func adotaBancoAntigo(path string) string {
	if _, err := os.Stat(path); err == nil {
		return path
	}
	pasta := filepath.Dir(path)
	for _, antigo := range []string{"valkyris.db", "valkyris.sqlite", "valky.sqlite"} {
		candidato := filepath.Join(pasta, antigo)
		if _, err := os.Stat(candidato); err != nil {
			continue
		}
		if err := os.Rename(candidato, path); err != nil {
			return candidato
		}
		for _, sufixo := range []string{"-wal", "-shm"} {
			_ = os.Rename(candidato+sufixo, path+sufixo)
		}
		return path
	}
	return path
}

func Open(path string) (*Store, error) {
	path = adotaBancoAntigo(path)
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	db.SetMaxOpenConns(1)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err = db.ExecContext(ctx, schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("apply schema: %w", err)
	}
	for _, migration := range []struct {
		table  string
		column string
		query  string
		after  string
	}{
		{"viewer_sessions", "is_admin", `ALTER TABLE viewer_sessions ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0`, ""},
		{"rules", "motion_json", `ALTER TABLE rules ADD COLUMN motion_json TEXT NOT NULL DEFAULT 'null'`, ""},
		{"cameras", "media_xaddr", `ALTER TABLE cameras ADD COLUMN media_xaddr TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "events_xaddr", `ALTER TABLE cameras ADD COLUMN events_xaddr TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "ptz_xaddr", `ALTER TABLE cameras ADD COLUMN ptz_xaddr TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "startup_preset_token", `ALTER TABLE cameras ADD COLUMN startup_preset_token TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "setup_status", `ALTER TABLE cameras ADD COLUMN setup_status TEXT NOT NULL DEFAULT 'ready'`, ""},
		{"cameras", "setup_step", `ALTER TABLE cameras ADD COLUMN setup_step TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "setup_error", `ALTER TABLE cameras ADD COLUMN setup_error TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "setup_updated_at", `ALTER TABLE cameras ADD COLUMN setup_updated_at TEXT NOT NULL DEFAULT ''`, `UPDATE cameras SET setup_updated_at=updated_at WHERE setup_updated_at=''`},
		{"cameras", "alerts_json", `ALTER TABLE cameras ADD COLUMN alerts_json TEXT NOT NULL DEFAULT '{}'`, ""},
		{"cameras", "icon", `ALTER TABLE cameras ADD COLUMN icon TEXT NOT NULL DEFAULT 'camera'`, ""},
		// Devices paired by releases without roles were trusted setup devices.
		{"devices", "is_admin", `ALTER TABLE devices ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0`, `UPDATE devices SET is_admin=1`},
		{"devices", "user_id", `ALTER TABLE devices ADD COLUMN user_id TEXT REFERENCES users(id) ON DELETE SET NULL`, ""},
		{"users", "username", `ALTER TABLE users ADD COLUMN username TEXT COLLATE NOCASE`, `CREATE UNIQUE INDEX idx_users_username ON users(username) WHERE username IS NOT NULL`},
		{"users", "password_hash", `ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT ''`, ""},
		{"users", "is_admin", `ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0`, `UPDATE users SET is_admin=1 WHERE EXISTS(SELECT 1 FROM devices WHERE devices.user_id=users.id AND devices.is_admin=1)`},
		{"users", "view_rules", `ALTER TABLE users ADD COLUMN view_rules INTEGER NOT NULL DEFAULT 0`, ""},
		{"users", "edit_rules", `ALTER TABLE users ADD COLUMN edit_rules INTEGER NOT NULL DEFAULT 0`, ""},
		{"viewer_sessions", "user_id", `ALTER TABLE viewer_sessions ADD COLUMN user_id TEXT REFERENCES users(id) ON DELETE CASCADE`, `DELETE FROM viewer_sessions`},
		{"users", "avatar_data", `ALTER TABLE users ADD COLUMN avatar_data TEXT NOT NULL DEFAULT ''`, ""},
		{"users", "battery", `ALTER TABLE users ADD COLUMN battery INTEGER NOT NULL DEFAULT -1`, ""},
		{"users", "battery_at", `ALTER TABLE users ADD COLUMN battery_at TEXT NOT NULL DEFAULT ''`, ""},
		{"users", "battery_notified_at", `ALTER TABLE users ADD COLUMN battery_notified_at TEXT NOT NULL DEFAULT ''`, ""},
		{"users", "notify_camera", `ALTER TABLE users ADD COLUMN notify_camera INTEGER NOT NULL DEFAULT 1`, ""},
		{"users", "notify_location", `ALTER TABLE users ADD COLUMN notify_location INTEGER NOT NULL DEFAULT 1`, ""},
		{"user_locations", "last_seen_at", `ALTER TABLE user_locations ADD COLUMN last_seen_at TEXT NOT NULL DEFAULT ''`, ""},
		{"user_locations", "address", `ALTER TABLE user_locations ADD COLUMN address TEXT NOT NULL DEFAULT ''`, ""},
		{"events", "clip_status", `ALTER TABLE events ADD COLUMN clip_status TEXT NOT NULL DEFAULT 'not_requested'`, `UPDATE events SET clip_status=CASE WHEN clip_path IS NULL OR clip_path='' THEN 'not_requested' ELSE 'ready' END`},
		{"events", "clip_error", `ALTER TABLE events ADD COLUMN clip_error TEXT`, ""},
		{"remotes", "mac", `ALTER TABLE remotes ADD COLUMN mac TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "favorite", `ALTER TABLE cameras ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0`, ""},
		{"lights", "favorite", `ALTER TABLE lights ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0`, ""},
		{"remotes", "favorite", `ALTER TABLE remotes ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0`, ""},
	} {
		exists, migrationErr := columnExists(ctx, db, migration.table, migration.column)
		if migrationErr != nil {
			db.Close()
			return nil, migrationErr
		}
		if !exists {
			tx, beginErr := db.BeginTx(ctx, nil)
			if beginErr != nil {
				db.Close()
				return nil, fmt.Errorf("begin migration %s.%s: %w", migration.table, migration.column, beginErr)
			}
			if _, migrationErr = tx.ExecContext(ctx, migration.query); migrationErr != nil {
				tx.Rollback()
				db.Close()
				return nil, fmt.Errorf("migrate %s.%s: %w", migration.table, migration.column, migrationErr)
			}
			if migration.after != "" {
				if _, migrationErr = tx.ExecContext(ctx, migration.after); migrationErr != nil {
					tx.Rollback()
					db.Close()
					return nil, fmt.Errorf("finalize migration %s.%s: %w", migration.table, migration.column, migrationErr)
				}
			}
			if migrationErr = tx.Commit(); migrationErr != nil {
				db.Close()
				return nil, fmt.Errorf("commit migration %s.%s: %w", migration.table, migration.column, migrationErr)
			}
		}
	}
	// O campo "cômodo" das lâmpadas saiu da interface; a coluna antiga é
	// removida das instalações que já existiam.
	for _, drop := range []struct{ table, column string }{
		{"lights", "room"},
	} {
		exists, dropErr := columnExists(ctx, db, drop.table, drop.column)
		if dropErr != nil {
			db.Close()
			return nil, dropErr
		}
		if exists {
			if _, dropErr = db.ExecContext(ctx, fmt.Sprintf("ALTER TABLE %s DROP COLUMN %s", drop.table, drop.column)); dropErr != nil {
				db.Close()
				return nil, fmt.Errorf("drop %s.%s: %w", drop.table, drop.column, dropErr)
			}
		}
	}
	if _, err = db.ExecContext(ctx, `CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices(user_id)`); err != nil {
		db.Close()
		return nil, fmt.Errorf("index device users: %w", err)
	}
	if err = migrateRulesWithoutConfidence(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	// Include motion settings in idempotency without modifying existing rules.
	if _, err = db.ExecContext(ctx, `CREATE UNIQUE INDEX IF NOT EXISTS idx_rules_motion_idempotency ON rules(camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json,motion_json); DROP INDEX IF EXISTS idx_rules_idempotency;`); err != nil {
		db.Close()
		return nil, fmt.Errorf("index motion rules: %w", err)
	}
	// Pausing rules was removed from the interface. Normalize old accidental
	// disabled values before serving the API again.
	if _, err = db.ExecContext(ctx, `UPDATE rules SET enabled=1 WHERE enabled=0`); err != nil {
		db.Close()
		return nil, fmt.Errorf("reactivate rules: %w", err)
	}
	if err = migrateEventsForTracking(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	if err = migrateUsersFromDevices(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	if _, err = db.ExecContext(ctx, `CREATE INDEX IF NOT EXISTS idx_events_epoch ON events(unixepoch(occurred_at))`); err != nil {
		db.Close()
		return nil, fmt.Errorf("index event activity: %w", err)
	}
	if err = migrateRuleAlerts(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{DB: db}, nil
}

// Devices existed before family profiles. Keep every device intact and create
// exactly one linked user for each legacy device that has no user yet.
func migrateUsersFromDevices(ctx context.Context, db *sql.DB) error {
	rows, err := db.QueryContext(ctx, `SELECT id,name,created_at FROM devices WHERE user_id IS NULL OR user_id=''`)
	if err != nil {
		return fmt.Errorf("find unlinked devices: %w", err)
	}
	defer rows.Close()
	type legacyDevice struct{ id, name, createdAt string }
	var devices []legacyDevice
	for rows.Next() {
		var deviceID, name, createdAt string
		if err := rows.Scan(&deviceID, &name, &createdAt); err != nil {
			return fmt.Errorf("scan unlinked device: %w", err)
		}
		devices = append(devices, legacyDevice{deviceID, name, createdAt})
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, device := range devices {
		userID := "user-" + device.id
		if _, err := db.ExecContext(ctx, `INSERT OR IGNORE INTO users(id,name,color,enabled,created_at,updated_at) VALUES(?,?,?,1,?,?)`, userID, device.name, "#5B5BD6", device.createdAt, device.createdAt); err != nil {
			return fmt.Errorf("create user for device: %w", err)
		}
		if _, err := db.ExecContext(ctx, `UPDATE devices SET user_id=? WHERE id=?`, userID, device.id); err != nil {
			return fmt.Errorf("link device to user: %w", err)
		}
		if _, err := db.ExecContext(ctx, `UPDATE users SET is_admin=1 WHERE id=? AND EXISTS(SELECT 1 FROM devices WHERE user_id=? AND is_admin=1)`, userID, userID); err != nil {
			return fmt.Errorf("migrate administrator account: %w", err)
		}
	}
	return nil
}

// Tracking events use the same notification and acknowledgement lifecycle as
// camera events. Older databases made events.camera_id mandatory, so rebuild
// that table once to make the relation optional while preserving every event
// and pending push delivery.
func migrateEventsForTracking(ctx context.Context, db *sql.DB) error {
	exists, err := columnExists(ctx, db, "events", "source")
	if err != nil || exists {
		return err
	}
	if _, err = db.ExecContext(ctx, `PRAGMA foreign_keys=OFF`); err != nil {
		return fmt.Errorf("disable event migration foreign keys: %w", err)
	}
	defer db.ExecContext(context.Background(), `PRAGMA foreign_keys=ON`)
	queries := []string{
		`ALTER TABLE push_deliveries RENAME TO push_deliveries_legacy`,
		`ALTER TABLE events RENAME TO events_legacy`,
		`CREATE TABLE events (
			id TEXT PRIMARY KEY,
			camera_id TEXT REFERENCES cameras(id) ON DELETE CASCADE,
			rule_id TEXT REFERENCES rules(id) ON DELETE SET NULL,
			source TEXT NOT NULL DEFAULT 'camera',
			subject_id TEXT NOT NULL DEFAULT '',
			type TEXT NOT NULL, confidence REAL NOT NULL, occurred_at TEXT NOT NULL,
			snapshot_path TEXT, clip_path TEXT, clip_status TEXT NOT NULL DEFAULT 'not_requested', clip_error TEXT, metadata_json TEXT NOT NULL DEFAULT '{}',
			acknowledged_at TEXT, acknowledged_by TEXT, created_at TEXT NOT NULL
		)`,
		`INSERT INTO events(id,camera_id,rule_id,source,subject_id,type,confidence,occurred_at,snapshot_path,clip_path,clip_status,clip_error,metadata_json,acknowledged_at,acknowledged_by,created_at)
		 SELECT id,camera_id,rule_id,'camera','',type,confidence,occurred_at,snapshot_path,clip_path,CASE WHEN clip_path IS NULL OR clip_path='' THEN 'not_requested' ELSE 'ready' END,NULL,metadata_json,acknowledged_at,acknowledged_by,created_at FROM events_legacy`,
		`CREATE INDEX idx_events_occurred ON events(occurred_at DESC)`,
		`CREATE TABLE push_deliveries (
			id TEXT PRIMARY KEY, event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
			device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE, attempts INTEGER NOT NULL DEFAULT 0,
			next_attempt_at TEXT NOT NULL, delivered_at TEXT, last_error TEXT, created_at TEXT NOT NULL
		)`,
		`INSERT INTO push_deliveries(id,event_id,device_id,attempts,next_attempt_at,delivered_at,last_error,created_at)
		 SELECT id,event_id,device_id,attempts,next_attempt_at,delivered_at,last_error,created_at FROM push_deliveries_legacy`,
		`DROP TABLE push_deliveries_legacy`,
		`DROP TABLE events_legacy`,
	}
	for _, query := range queries {
		if _, err = db.ExecContext(ctx, query); err != nil {
			return fmt.Errorf("migrate events for tracking: %w", err)
		}
	}
	return nil
}

// Confidence remains attached to detected events for observability, but it is
// deliberately not a rule criterion. This migration preserves every rule and
// rebuilds the idempotency index without the retired column.
func migrateRulesWithoutConfidence(ctx context.Context, db *sql.DB) error {
	exists, err := columnExists(ctx, db, "rules", "min_confidence")
	if err != nil || !exists {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin rules confidence migration: %w", err)
	}
	defer tx.Rollback()
	for _, query := range []string{
		`DROP INDEX IF EXISTS idx_rules_idempotency`,
		`ALTER TABLE rules DROP COLUMN min_confidence`,
		`CREATE UNIQUE INDEX idx_rules_idempotency ON rules(camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json)`,
	} {
		if _, err = tx.ExecContext(ctx, query); err != nil {
			return fmt.Errorf("migrate rules without confidence: %w", err)
		}
	}
	return tx.Commit()
}

func columnExists(ctx context.Context, db *sql.DB, table, column string) (bool, error) {
	rows, err := db.QueryContext(ctx, "PRAGMA table_info("+table+")")
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var cid, notNull, primaryKey int
		var name, kind string
		var defaultValue any
		if err = rows.Scan(&cid, &name, &kind, &notNull, &defaultValue, &primaryKey); err != nil {
			return false, err
		}
		if name == column {
			return true, nil
		}
	}
	return false, rows.Err()
}

func (s *Store) Close() error { return s.DB.Close() }

func NullTime(value sql.NullString) *time.Time {
	if !value.Valid {
		return nil
	}
	t, err := time.Parse(time.RFC3339Nano, value.String)
	if err != nil {
		return nil
	}
	return &t
}

// Copy the previous camera settings once, preserving per-rule overrides and
// explicit resets. New rules use their own defaults after migration.
func migrateRuleAlerts(ctx context.Context, db *sql.DB) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var done int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM settings WHERE key='rule_alerts_migrated'`).Scan(&done); err != nil {
		return err
	}
	if done != 0 {
		return nil
	}
	if _, err = tx.ExecContext(ctx, `UPDATE rules SET actions_json=json_set(actions_json,'$.alerts',json((SELECT alerts_json FROM cameras WHERE cameras.id=rules.camera_id))) WHERE json_type(actions_json,'$.alerts') IS NULL AND EXISTS(SELECT 1 FROM cameras WHERE cameras.id=rules.camera_id AND alerts_json<>'{}')`); err != nil {
		return fmt.Errorf("migrate rule alerts: %w", err)
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO settings(key,value,updated_at) VALUES('rule_alerts_migrated','1',?)`, time.Now().UTC().Format(time.RFC3339Nano)); err != nil {
		return err
	}
	return tx.Commit()
}
