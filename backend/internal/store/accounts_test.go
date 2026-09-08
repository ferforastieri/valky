package store

import (
	"database/sql"
	"testing"
)

func TestAccountMigrationPreservesUsersAndNativeSessions(t *testing.T) {
	path := t.TempDir() + "/legacy.db"
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = old.Exec(schema); err != nil {
		t.Fatal(err)
	}
	_, err = old.Exec(`INSERT INTO users(id,name,color,created_at,updated_at) VALUES('original','Miriam','#fff','now','now');
 INSERT INTO devices(id,user_id,name,token_hash,is_admin,created_at,last_seen_at) VALUES('phone','original','Phone',x'01',1,'now','now');
 INSERT INTO viewer_sessions(id,token_hash,expires_at,created_at) VALUES('old-browser',x'02','future','now');`)
	if err != nil {
		t.Fatal(err)
	}
	old.Close()
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	var name string
	var admin bool
	if err = db.DB.QueryRow("SELECT name,is_admin FROM users WHERE id='original'").Scan(&name, &admin); err != nil || name != "Miriam" || !admin {
		t.Fatalf("profile/role lost: %s %v %v", name, admin, err)
	}
	var count int
	db.DB.QueryRow("SELECT count(*) FROM devices WHERE id='phone' AND user_id='original'").Scan(&count)
	if count != 1 {
		t.Fatal("native session removed")
	}
	db.DB.QueryRow("SELECT count(*) FROM viewer_sessions").Scan(&count)
	if count != 0 {
		t.Fatal("unowned legacy browser retained")
	}
	db.DB.Exec("UPDATE users SET username='miriam',password_hash='hash' WHERE id='original'")
	db.Close()
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var username, hash string
	if err = db.DB.QueryRow("SELECT username,password_hash FROM users WHERE id='original'").Scan(&username, &hash); err != nil || username != "miriam" || hash != "hash" {
		t.Fatal("reopening overwrote credentials")
	}
}
