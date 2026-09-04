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
	return &Store{DB: db}, nil
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
