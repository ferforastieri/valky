package store

import (
	"context"
	"database/sql"
	"testing"

	_ "modernc.org/sqlite"
)

func TestOpenMigratesONVIFServiceAddresses(t *testing.T) {
	path := t.TempDir() + "/legacy.db"
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = legacy.Exec(`CREATE TABLE cameras (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL DEFAULT 2020,
      username_enc BLOB NOT NULL, password_enc BLOB NOT NULL, rtsp_uri_enc BLOB NOT NULL,
      profile_token TEXT NOT NULL DEFAULT '', capabilities_json TEXT NOT NULL DEFAULT '{}',
      enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    )`)
	legacy.Close()
	if err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, column := range []string{"media_xaddr", "events_xaddr", "ptz_xaddr", "icon"} {
		exists, checkErr := columnExists(context.Background(), db.DB, "cameras", column)
		if checkErr != nil || !exists {
			t.Fatalf("column %s was not migrated: %v", column, checkErr)
		}
	}
}

func TestOpenPromotesLegacyDevicesWithoutChangingNewDefault(t *testing.T) {
	path := t.TempDir() + "/legacy-devices.db"
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = legacy.Exec(`CREATE TABLE devices (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash BLOB NOT NULL UNIQUE,
      push_endpoint_enc BLOB, push_secret_enc BLOB, locale TEXT NOT NULL DEFAULT 'pt-BR',
      enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, last_seen_at TEXT
    ); INSERT INTO devices(id,name,token_hash,created_at) VALUES('legacy','Owner',x'01','now')`)
	legacy.Close()
	if err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var legacyAdmin int
	if err = db.DB.QueryRow(`SELECT is_admin FROM devices WHERE id='legacy'`).Scan(&legacyAdmin); err != nil || legacyAdmin != 1 {
		t.Fatalf("legacy device was not promoted: admin=%d err=%v", legacyAdmin, err)
	}
	if _, err = db.DB.Exec(`INSERT INTO devices(id,name,token_hash,created_at) VALUES('new','Guest',x'02','now')`); err != nil {
		t.Fatal(err)
	}
	var newAdmin int
	if err = db.DB.QueryRow(`SELECT is_admin FROM devices WHERE id='new'`).Scan(&newAdmin); err != nil || newAdmin != 0 {
		t.Fatalf("new device did not retain least-privilege default: admin=%d err=%v", newAdmin, err)
	}
}
