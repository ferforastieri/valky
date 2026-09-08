package notify

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/event"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"testing"
	"time"
)

func TestRuleRecipients(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/recipients.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.DB.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Room','local',x'01',x'01',x'01',?,?)`, now, now)
	for _, id := range []string{"a", "b"} {
		exec(`INSERT INTO users(id,name,created_at,updated_at) VALUES(?,?,?,?)`, id, id, now, now)
		exec(`INSERT INTO devices(id,name,token_hash,user_id,push_endpoint_enc,created_at,last_seen_at) VALUES(?,?,?,?,x'01',?,?)`, id, id, []byte(id), id, now, now)
	}
	rs := rules.NewService(db)
	rule, err := rs.Create(ctx, rules.Rule{CameraID: "cam", Name: "Cry", DetectorTypes: []string{"baby_cry"}, Actions: rules.Actions{Notify: true}})
	if err != nil {
		t.Fatal(err)
	}
	es := event.NewService(db)
	e, err := es.Create(ctx, event.Event{CameraID: "cam", RuleID: &rule.ID, Type: "baby_cry"})
	if err != nil {
		t.Fatal(err)
	}
	s := NewService(db, nil, "")
	check := func(ids []string, want int) {
		t.Helper()
		exec(`DELETE FROM push_deliveries`)
		if err := rs.SetRecipients(ctx, rule.ID, ids); err != nil {
			t.Fatal(err)
		}
		if err := s.Enqueue(ctx, e); err != nil {
			t.Fatal(err)
		}
		var got int
		if err := db.DB.QueryRow(`SELECT count(*) FROM push_deliveries`).Scan(&got); err != nil || got != want {
			t.Fatalf("ids=%v got=%d want=%d err=%v", ids, got, want, err)
		}
	}
	check(nil, 2)
	check([]string{"a"}, 1)
	check([]string{}, 0)
	exec(`UPDATE users SET enabled=0 WHERE id='b'`)
	check(nil, 1)
	if err := rs.SetRecipients(ctx, rule.ID, []string{"missing"}); err == nil {
		t.Fatal("unknown recipient accepted")
	}
	if err := rs.SetRecipients(ctx, rule.ID, []string{"b"}); err == nil {
		t.Fatal("disabled recipient accepted")
	}
}
