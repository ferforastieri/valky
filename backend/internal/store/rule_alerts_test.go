package store

import (
	"context"
	"testing"
	"time"
)

func TestCameraAlertsMigrateOnceWithoutReplacingRuleOverrides(t *testing.T) {
	db, err := Open(t.TempDir() + "/migration.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,alerts_json,created_at,updated_at) VALUES('cam','Room','host',x'01',x'01',x'01','{"alarmTitle":"Legacy"}',?,?)`, now, now)
	if err != nil {
		t.Fatal(err)
	}
	for id, actions := range map[string]string{"legacy": "{}", "override": `{"alerts":{"alarmTitle":"Own"}}`} {
		_, err = db.DB.Exec(`INSERT INTO rules(id,camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json,created_at,updated_at) VALUES(?,'cam',?,'["motion"]',1,60,'{}',?,?,?)`, id, id, actions, now, now)
		if err != nil {
			t.Fatal(err)
		}
	}
	_, err = db.DB.Exec(`DELETE FROM settings WHERE key='rule_alerts_migrated'`)
	if err != nil {
		t.Fatal(err)
	}
	if err = migrateRuleAlerts(context.Background(), db.DB); err != nil {
		t.Fatal(err)
	}
	for id, want := range map[string]string{"legacy": "Legacy", "override": "Own"} {
		var got string
		if err = db.DB.QueryRow(`SELECT json_extract(actions_json,'$.alerts.alarmTitle') FROM rules WHERE id=?`, id).Scan(&got); err != nil || got != want {
			t.Fatalf("%s got %s err %v", id, got, err)
		}
	}
	_, err = db.DB.Exec(`UPDATE rules SET actions_json='{}' WHERE id='legacy'`)
	if err != nil {
		t.Fatal(err)
	}
	if err = migrateRuleAlerts(context.Background(), db.DB); err != nil {
		t.Fatal(err)
	}
	var got string
	if err = db.DB.QueryRow(`SELECT actions_json FROM rules WHERE id='legacy'`).Scan(&got); err != nil || got != "{}" {
		t.Fatalf("migration repeated: %s %v", got, err)
	}
}
