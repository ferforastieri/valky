package store

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schema string

type Store struct{ DB *sql.DB }

func Open(path string) (*Store, error) {
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
		{"cameras", "media_xaddr", `ALTER TABLE cameras ADD COLUMN media_xaddr TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "events_xaddr", `ALTER TABLE cameras ADD COLUMN events_xaddr TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "ptz_xaddr", `ALTER TABLE cameras ADD COLUMN ptz_xaddr TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "setup_status", `ALTER TABLE cameras ADD COLUMN setup_status TEXT NOT NULL DEFAULT 'ready'`, ""},
		{"cameras", "setup_step", `ALTER TABLE cameras ADD COLUMN setup_step TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "setup_error", `ALTER TABLE cameras ADD COLUMN setup_error TEXT NOT NULL DEFAULT ''`, ""},
		{"cameras", "setup_updated_at", `ALTER TABLE cameras ADD COLUMN setup_updated_at TEXT NOT NULL DEFAULT ''`, `UPDATE cameras SET setup_updated_at=updated_at WHERE setup_updated_at=''`},
		{"cameras", "icon", `ALTER TABLE cameras ADD COLUMN icon TEXT NOT NULL DEFAULT 'camera'`, ""},
		// Devices paired by releases without roles were trusted setup devices.
		{"devices", "is_admin", `ALTER TABLE devices ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0`, `UPDATE devices SET is_admin=1`},
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
	if err = migrateRulesWithoutConfidence(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	if err = migrateEventsForTracking(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{DB: db}, nil
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
			snapshot_path TEXT, clip_path TEXT, metadata_json TEXT NOT NULL DEFAULT '{}',
			acknowledged_at TEXT, acknowledged_by TEXT, created_at TEXT NOT NULL
		)`,
		`INSERT INTO events(id,camera_id,rule_id,source,subject_id,type,confidence,occurred_at,snapshot_path,clip_path,metadata_json,acknowledged_at,acknowledged_by,created_at)
		 SELECT id,camera_id,rule_id,'camera','',type,confidence,occurred_at,snapshot_path,clip_path,metadata_json,acknowledged_at,acknowledged_by,created_at FROM events_legacy`,
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
