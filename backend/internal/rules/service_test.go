package rules

import (
	"context"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestActiveAtOvernight(t *testing.T) {
	s := Schedule{Days: []int{1}, Start: "22:00", End: "06:00", Timezone: "UTC"}
	mondayLate := time.Date(2026, 8, 31, 23, 0, 0, 0, time.UTC)
	if !activeAt(s, mondayLate) {
		t.Fatal("expected overnight schedule active")
	}
	mondayNoon := time.Date(2026, 8, 31, 12, 0, 0, 0, time.UTC)
	if activeAt(s, mondayNoon) {
		t.Fatal("expected schedule inactive")
	}
}

func TestConfirmationsConfidenceAndCooldown(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/rules.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	nowText := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Nursery','10.0.0.3',x'01',x'01',x'01',?,?)`, nowText, nowText); err != nil {
		t.Fatal(err)
	}
	service := NewService(db)
	rule, err := service.Create(context.Background(), Rule{CameraID: "cam", Name: "Baby", DetectorTypes: []string{"baby_cry"}, MinConfidence: .8, Confirmations: 2, CooldownSeconds: 60})
	if err != nil {
		t.Fatal(err)
	}
	if rule.Schedule.Days == nil {
		t.Fatal("created rule serialized an empty schedule as null")
	}
	listed, err := service.List(context.Background(), "cam")
	if err != nil || len(listed) != 1 || listed[0].Schedule.Days == nil {
		t.Fatalf("rule list did not normalize empty schedule days: %#v err=%v", listed, err)
	}
	now := time.Now().UTC()
	if matched, _ := service.Match(context.Background(), Detection{CameraID: "cam", Type: "baby_cry", Confidence: .7, OccurredAt: now}); len(matched) != 0 {
		t.Fatal("below-threshold detection matched")
	}
	if matched, _ := service.Match(context.Background(), Detection{CameraID: "cam", Type: "baby_cry", Confidence: .9, OccurredAt: now}); len(matched) != 0 {
		t.Fatal("first confirmation matched")
	}
	matched, err := service.Match(context.Background(), Detection{CameraID: "cam", Type: "baby_cry", Confidence: .9, OccurredAt: now.Add(time.Second)})
	if err != nil || len(matched) != 1 || matched[0].ID != rule.ID {
		t.Fatalf("second confirmation did not match: %#v err=%v", matched, err)
	}
	for index := 0; index < 2; index++ {
		matched, err = service.Match(context.Background(), Detection{CameraID: "cam", Type: "baby_cry", Confidence: .95, OccurredAt: now.Add(10 * time.Second)})
		if err != nil || len(matched) != 0 {
			t.Fatalf("cooldown did not suppress duplicate: %#v err=%v", matched, err)
		}
	}
}

func TestCreateIsIdempotent(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/rules.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	nowText := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Nursery','10.0.0.3',x'01',x'01',x'01',?,?)`, nowText, nowText); err != nil {
		t.Fatal(err)
	}
	service := NewService(db)
	input := Rule{CameraID: "cam", Name: "Baby", DetectorTypes: []string{"baby_cry", "motion"}, MinConfidence: .8, Confirmations: 2, CooldownSeconds: 60}
	first, err := service.Create(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.Create(context.Background(), Rule{CameraID: "cam", Name: " Baby ", DetectorTypes: []string{"motion", "baby_cry"}, MinConfidence: .8, Confirmations: 2, CooldownSeconds: 60})
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != second.ID {
		t.Fatalf("expected the existing rule, got %q then %q", first.ID, second.ID)
	}
	var count int
	if err := db.DB.QueryRow(`SELECT count(*) FROM rules`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("expected one rule, got %d", count)
	}
}

func TestSimpleRuleTriggersOnceWithoutUserTuning(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/rules.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC()
	stamp := now.Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Room','10.0.0.3',x'01',x'01',x'01',?,?)`, stamp, stamp); err != nil {
		t.Fatal(err)
	}
	s := NewService(db)
	for _, kind := range []string{"motion", "baby_cry", "dog_bark"} {
		rule, err := s.Create(context.Background(), Rule{CameraID: "cam", Name: kind, DetectorTypes: []string{kind}})
		if err != nil {
			t.Fatal(err)
		}
		if rule.Confirmations != 1 {
			t.Fatalf("requires extra confirmations: %#v", rule)
		}
		d := Detection{CameraID: "cam", Type: kind, Confidence: .9, OccurredAt: now}
		if kind == "motion" {
			d.Confidence = .15
			d.Metadata = map[string]any{"source": "visual_fallback"}
		}
		matched, err := s.Match(context.Background(), d)
		if err != nil || len(matched) != 1 {
			t.Fatalf("%s did not trigger: %#v %v", kind, matched, err)
		}
		matched, err = s.Match(context.Background(), d)
		if err != nil || len(matched) != 0 {
			t.Fatalf("%s cooldown failed: %#v %v", kind, matched, err)
		}
	}
}
