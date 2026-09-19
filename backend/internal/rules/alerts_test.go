package rules

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/camera"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"strings"
	"testing"
	"time"
)

func TestRuleAlertsAreIndependentAndLegacyEditsPreserveThem(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/rules.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Room','host',x'01',x'01',x'01',?,?)`, now, now)
	if err != nil {
		t.Fatal(err)
	}
	s := NewService(db)
	ctx := context.Background()
	a := camera.DefaultAlertPresentation()
	a.NotificationTitle = "Cry detected"
	first, err := s.Create(ctx, Rule{CameraID: "cam", Name: "Cry", DetectorTypes: []string{"baby_cry"}, Actions: Actions{Alerts: &a}})
	if err != nil {
		t.Fatal(err)
	}
	b := camera.DefaultAlertPresentation()
	b.NotificationTitle = "Motion detected"
	second, err := s.Create(ctx, Rule{CameraID: "cam", Name: "Movement", DetectorTypes: []string{"motion"}, Actions: Actions{Alerts: &b}})
	if err != nil {
		t.Fatal(err)
	}
	first.Actions.Alerts = nil
	first.Name = "Renamed"
	first, err = s.Update(ctx, first.ID, first)
	if err != nil {
		t.Fatal(err)
	}
	if first.Actions.Alerts.NotificationTitle != "Cry detected" || second.Actions.Alerts.NotificationTitle != "Motion detected" {
		t.Fatal("rule alerts overwritten")
	}
	reset := camera.DefaultAlertPresentation()
	first.Actions.Alerts = &reset
	first, err = s.Update(ctx, first.ID, first)
	if err != nil || first.Actions.Alerts.NotificationTitle != "" {
		t.Fatalf("reset failed: %v", err)
	}
	first.Actions.Alerts.NotificationTitle = strings.Repeat("x", 81)
	if _, err = s.Update(ctx, first.ID, first); err == nil {
		t.Fatal("invalid title accepted")
	}
}
