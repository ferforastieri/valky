package rules

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"testing"
	"time"
)

func motionService(t *testing.T) (*Service, Rule) {
	t.Helper()
	db, err := store.Open(t.TempDir() + "/motion.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	stamp := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Room','10.0.0.3',x'01',x'01',x'01',?,?)`, stamp, stamp)
	if err != nil {
		t.Fatal(err)
	}
	s := NewService(db)
	r, err := s.Create(context.Background(), Rule{CameraID: "cam", Name: "Berço", DetectorTypes: []string{"motion"}, Motion: &MotionSettings{Region: Region{X: .2, Y: .2, Width: .4, Height: .4}, MinDurationSeconds: 10, MinChangedFraction: .05}})
	if err != nil {
		t.Fatal(err)
	}
	return s, r
}
func TestRegionMotionRequiresContinuousEvidence(t *testing.T) {
	s, r := motionService(t)
	now := time.Now().UTC()
	send := func(second int, score float64) int {
		t.Helper()
		matched, err := s.Match(context.Background(), Detection{CameraID: r.CameraID, Type: "motion", Confidence: score, OccurredAt: now.Add(time.Duration(second) * time.Second), Motion: &MotionSample{RuleID: r.ID, RuleUpdatedAt: r.UpdatedAt, ChangedFraction: score}})
		if err != nil {
			t.Fatal(err)
		}
		return len(matched)
	}
	// Native camera events cannot bypass region or duration checks.
	matched, err := s.Match(context.Background(), Detection{CameraID: "cam", Type: "motion", Confidence: 1, OccurredAt: now, Metadata: map[string]any{"source": "onvif"}})
	if err != nil || len(matched) != 0 {
		t.Fatal("native event triggered region rule", err)
	}
	for _, v := range []struct {
		sec   int
		score float64
		want  int
	}{{0, .1, 0}, {4, .1, 0}, {6, 0, 0}, {8, .1, 0}, {12, .1, 0}, {16, .1, 0}, {18, .1, 1}, {20, .1, 0}, {80, .1, 0}} {
		if got := send(v.sec, v.score); got != v.want {
			t.Fatalf("at %ds got %d want %d", v.sec, got, v.want)
		}
	}
}
func TestMotionSampleGapAndRuleEditResetPersistence(t *testing.T) {
	s, r := motionService(t)
	now := time.Now().UTC()
	send := func(at time.Time, revision time.Time) int {
		matched, err := s.Match(context.Background(), Detection{CameraID: "cam", Type: "motion", OccurredAt: at, Motion: &MotionSample{RuleID: r.ID, RuleUpdatedAt: revision, ChangedFraction: .1}})
		if err != nil {
			t.Fatal(err)
		}
		return len(matched)
	}
	if send(now, r.UpdatedAt) != 0 || send(now.Add(30*time.Second), r.UpdatedAt) != 0 {
		t.Fatal("gap counted as motion")
	}
	original := r.UpdatedAt
	var err error
	r, err = s.Update(context.Background(), r.ID, r)
	if err != nil {
		t.Fatal(err)
	}
	if send(now.Add(40*time.Second), original) != 0 {
		t.Fatal("old settings triggered edited rule")
	}
	if send(now.Add(42*time.Second), r.UpdatedAt) != 0 {
		t.Fatal("edit did not reset persistence")
	}
}
func TestMotionSettingsRoundTripAndIdempotency(t *testing.T) {
	s, r := motionService(t)
	r.Schedule = Schedule{Days: []int{1, 3}, Start: "22:00", End: "06:00", Timezone: "America/Sao_Paulo"}
	r.CooldownSeconds = 120
	updated, err := s.Update(context.Background(), r.ID, r)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Motion == nil || *updated.Motion != *r.Motion || updated.Schedule.Start != "22:00" || updated.CooldownSeconds != 120 {
		t.Fatalf("lost settings: %+v", updated)
	}
	again, err := s.Create(context.Background(), r)
	if err != nil || again.ID != r.ID {
		t.Fatalf("not idempotent: %+v %v", again, err)
	}
	changed := *r.Motion
	changed.Region.X = .3
	r.Motion = &changed
	other, err := s.Create(context.Background(), r)
	if err != nil || other.ID == r.ID {
		t.Fatalf("distinct region collided: %+v %v", other, err)
	}
	// Distinct regions remain separate rules.
	var count int
	if err := s.store.DB.QueryRow(`SELECT count(*) FROM rules`).Scan(&count); err != nil || count != 2 {
		t.Fatalf("count %d %v", count, err)
	}
}
func TestScheduleUsesStartDayAndTimezone(t *testing.T) {
	s := Schedule{Days: []int{1}, Start: "22:00", End: "06:00", Timezone: "America/Sao_Paulo"}
	for _, v := range []struct {
		at   string
		want bool
	}{{"2026-09-01T02:00:00Z", true}, {"2026-09-01T08:59:00Z", true}, {"2026-09-01T09:00:00Z", false}, {"2026-08-31T08:00:00Z", false}, {"2026-09-01T15:00:00Z", false}} {
		at, _ := time.Parse(time.RFC3339, v.at)
		if activeAt(s, at) != v.want {
			t.Fatalf("%s", v.at)
		}
	}
}
func TestRejectInvalidMotionAndSchedules(t *testing.T) {
	cases := []Rule{
		{Schedule: Schedule{Days: []int{1}, Start: "25:00", End: "06:00", Timezone: "UTC"}},
		{Schedule: Schedule{Days: []int{7}, Start: "22:00", End: "06:00", Timezone: "UTC"}},
		{Schedule: Schedule{Days: []int{1}, Start: "22:00", End: "06:00", Timezone: "invalid"}},
		{Schedule: Schedule{Days: []int{1}, Start: "22:00", End: "22:00", Timezone: "UTC"}},
		{Motion: &MotionSettings{Region: Region{X: .9, Width: .5, Height: .5}, MinDurationSeconds: 10, MinChangedFraction: .1}},
		{Motion: &MotionSettings{Region: Region{Width: .5, Height: .5}, MinDurationSeconds: 0, MinChangedFraction: .1}},
		{CooldownSeconds: -1},
	}
	for _, r := range cases {
		r.CameraID = "cam"
		r.Name = "Test"
		r.DetectorTypes = []string{"motion"}
		if normalizeRule(&r) == nil {
			t.Fatalf("accepted %+v", r)
		}
	}
}

func TestMotionRulesSurviveDatabaseReopen(t *testing.T) {
	path := t.TempDir() + "/rules.db"
	db, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	stamp := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Room','10.0.0.3',x'01',x'01',x'01',?,?)`, stamp, stamp)
	if err != nil {
		t.Fatal(err)
	}
	s := NewService(db)
	for _, x := range []float64{.1, .2} {
		_, err = s.Create(context.Background(), Rule{CameraID: "cam", Name: "Region", DetectorTypes: []string{"motion"}, Motion: &MotionSettings{Region: Region{X: x, Width: .4, Height: .4}, MinDurationSeconds: 10, MinChangedFraction: .05}})
		if err != nil {
			t.Fatal(err)
		}
	}
	db.Close()
	db, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	all, err := NewService(db).List(context.Background(), "cam")
	if err != nil || len(all) != 2 {
		t.Fatalf("reopen lost rules: %+v %v", all, err)
	}
}
