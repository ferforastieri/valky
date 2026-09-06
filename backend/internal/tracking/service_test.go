package tracking

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestReportKeepsHistoryAndCreatesTransitions(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "valkyris.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	service := New(db)
	person, err := service.CreatePerson(context.Background(), Person{Name: "Fernando"}, "device-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.CreatePlace(context.Background(), Place{Name: "Casa", Latitude: -23.0, Longitude: -46.0, RadiusMeters: 100}); err != nil {
		t.Fatal(err)
	}
	transitions, err := service.Report(context.Background(), person.ID, "device-1", Location{Latitude: -23.0, Longitude: -46.0, Accuracy: 8})
	if err != nil || len(transitions) != 0 {
		t.Fatalf("initial location transitions=%d err=%v", len(transitions), err)
	}
	transitions, err = service.Report(context.Background(), person.ID, "device-1", Location{Latitude: -23.01, Longitude: -46.01, Accuracy: 8})
	if err != nil || len(transitions) != 1 || transitions[0].Entered {
		t.Fatalf("exit transitions=%+v err=%v", transitions, err)
	}
	history, err := service.History(context.Background(), person.ID, 10)
	if err != nil || len(history) != 2 {
		t.Fatalf("history=%d err=%v", len(history), err)
	}
}
