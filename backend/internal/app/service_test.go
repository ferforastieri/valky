package app

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/ferforastieri/valky/backend/internal/api"
	"github.com/ferforastieri/valky/backend/internal/event"
	"github.com/ferforastieri/valky/backend/internal/notify"
	"github.com/ferforastieri/valky/backend/internal/rules"
	"github.com/ferforastieri/valky/backend/internal/store"
)

func TestAlarmIsLimitedToScheduleButEventHistoryIsNot(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/app.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	nowText := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Nursery','10.0.0.3',x'01',x'01',x'01',?,?)`, nowText, nowText); err != nil {
		t.Fatal(err)
	}
	rulesService := rules.NewService(db)
	if _, err = rulesService.Create(context.Background(), rules.Rule{
		CameraID:        "cam",
		Name:            "Night",
		DetectorTypes:   []string{"motion"},
		CooldownSeconds: 10,
		Schedule:        rules.Schedule{Days: []int{1}, Start: "22:00", End: "06:00", Timezone: "UTC"},
		Actions:         rules.Actions{Record: false, Notify: false, Alarm: true},
	}); err != nil {
		t.Fatal(err)
	}
	service := &Service{
		Rules:   rulesService,
		Events:  event.NewService(db),
		Notify:  notify.NewService(db, nil, ""),
		Hub:     api.NewHub(),
		Logger:  slog.Default(),
		DataDir: t.TempDir(),
	}
	submit := func(at time.Time) event.Event {
		t.Helper()
		created, err := service.Submit(context.Background(), rules.Detection{
			CameraID: "cam", Type: "motion", Confidence: 1, OccurredAt: at,
			Metadata: map[string]any{"source": "onvif"},
		})
		if err != nil || len(created) != 1 {
			t.Fatalf("event not created at %s: %#v err=%v", at, created, err)
		}
		return created[0]
	}
	outside := submit(time.Date(2026, 8, 31, 12, 0, 0, 0, time.UTC))
	if alarm, _ := outside.Metadata["alarm"].(bool); alarm {
		t.Fatal("alarm escaped the schedule")
	}
	inside := submit(time.Date(2026, 8, 31, 23, 0, 0, 0, time.UTC))
	if alarm, _ := inside.Metadata["alarm"].(bool); !alarm {
		t.Fatal("alarm was not allowed inside the schedule")
	}
}
