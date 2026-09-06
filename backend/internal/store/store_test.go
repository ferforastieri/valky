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
	var userID, userName string
	if err = db.DB.QueryRow(`SELECT d.user_id,u.name FROM devices d JOIN users u ON u.id=d.user_id WHERE d.id='legacy'`).Scan(&userID, &userName); err != nil || userID == "" || userName != "Owner" {
		t.Fatalf("legacy device was not linked to a user: id=%q name=%q err=%v", userID, userName, err)
	}
	if _, err = db.DB.Exec(`INSERT INTO devices(id,name,token_hash,created_at) VALUES('new','Guest',x'02','now')`); err != nil {
		t.Fatal(err)
	}
	var newAdmin int
	if err = db.DB.QueryRow(`SELECT is_admin FROM devices WHERE id='new'`).Scan(&newAdmin); err != nil || newAdmin != 0 {
		t.Fatalf("new device did not retain least-privilege default: admin=%d err=%v", newAdmin, err)
	}
}

func TestOpenRemovesRuleConfidenceCriterion(t *testing.T) {
	path := t.TempDir() + "/legacy-rules.db"
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = legacy.Exec(`CREATE TABLE rules (
      id TEXT PRIMARY KEY, camera_id TEXT NOT NULL, name TEXT NOT NULL,
      detector_types_json TEXT NOT NULL, min_confidence REAL NOT NULL,
      confirmations INTEGER NOT NULL, cooldown_seconds INTEGER NOT NULL,
      schedule_json TEXT NOT NULL, actions_json TEXT NOT NULL,
      enabled INTEGER NOT NULL DEFAULT 1, last_triggered_at TEXT,
      created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    ); CREATE UNIQUE INDEX idx_rules_idempotency
      ON rules(camera_id,name,detector_types_json,min_confidence,confirmations,cooldown_seconds,schedule_json,actions_json)`)
	legacy.Close()
	if err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if exists, checkErr := columnExists(context.Background(), db.DB, "rules", "min_confidence"); checkErr != nil || exists {
		t.Fatalf("confidence column was not removed: exists=%v err=%v", exists, checkErr)
	}
}
