package event

import (
	"context"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestAcknowledgeAll(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/events.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Camera','10.0.0.3',x'01',x'01',x'01',?,?)`, now, now); err != nil {
		t.Fatal(err)
	}
	service := NewService(db)
	for range 2 {
		if _, err = service.Create(context.Background(), Event{CameraID: "cam", Type: "motion", Confidence: .9}); err != nil {
			t.Fatal(err)
		}
	}
	count, err := service.AcknowledgeAll(context.Background(), "device")
	if err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatalf("expected 2 acknowledged events, got %d", count)
	}
	events, err := service.List(context.Background(), "", 10)
	if err != nil {
		t.Fatal(err)
	}
	for _, event := range events {
		if event.AcknowledgedAt == nil || event.AcknowledgedBy != "device" {
			t.Fatalf("event was not acknowledged: %#v", event)
		}
	}
	count, err = service.AcknowledgeAll(context.Background(), "device")
	if err != nil || count != 0 {
		t.Fatalf("expected no remaining events, got count=%d err=%v", count, err)
	}
}
