package rules

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"testing"
	"time"
)

func TestAudioRuleConfirmations(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/rules.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC()
	_, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Nursery','10.0.0.3',x'01',x'01',x'01',?,?)`, now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	if err != nil {
		t.Fatal(err)
	}
	s := NewService(db)
	_, err = s.Create(context.Background(), Rule{CameraID: "cam", Name: "Baby", DetectorTypes: []string{"baby_cry"}, Confirmations: 2, CooldownSeconds: 60})
	if err != nil {
		t.Fatal(err)
	}
	check := func(start int, session string, ready bool, want int) {
		t.Helper()
		a := &AudioSample{Start: now.Add(time.Duration(start) * time.Second), End: now.Add(time.Duration(start+4) * time.Second), Session: session, TemporalAccepted: ready}
		got, err := s.Match(context.Background(), Detection{CameraID: "cam", Type: "baby_cry", Confidence: .6, Audio: a, OccurredAt: a.End})
		if err != nil || len(got) != want {
			t.Fatalf("start=%d ready=%v got=%d want=%d err=%v", start, ready, len(got), want, err)
		}
	}
	check(0, "s", true, 0)
	check(2, "s", true, 0)  // Overlap cannot confirm itself.
	check(4, "s", false, 0) // Quiet resets evidence.
	check(6, "s", true, 0)
	check(10, "reconnected", true, 0)
	check(14, "reconnected", true, 1)
	check(18, "reconnected", true, 0) // Cooldown.
	check(80, "reconnected", true, 0)
	check(90, "reconnected", true, 0) // Capture gap resets evidence.
	check(94, "reconnected", true, 1)
}
