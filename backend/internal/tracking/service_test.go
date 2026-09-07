package tracking

import (
	"context"
	"path/filepath"
	"testing"
	"time"

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

func TestReportMyLocationPersistsResolvedAddress(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "valkyris.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := db.DB.ExecContext(context.Background(), `INSERT INTO users(id,name,created_at,updated_at) VALUES(?,?,?,?)`, "user-1", "Fernando", now, now); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(context.Background(), `INSERT INTO devices(id,user_id,name,token_hash,created_at) VALUES(?,?,?,?,?)`, "device-1", "user-1", "Telefone", []byte("token-1"), now); err != nil {
		t.Fatal(err)
	}
	service := New(db)
	if _, err := service.ReportMyLocation(context.Background(), "device-1", UserLocation{
		Latitude: -23.5505, Longitude: -46.6333, Accuracy: 8, Address: "Avenida Paulista, São Paulo - SP",
	}); err != nil {
		t.Fatal(err)
	}
	history, err := service.UserHistory(context.Background(), "user-1", 10)
	if err != nil || len(history) != 1 {
		t.Fatalf("history=%d err=%v", len(history), err)
	}
	if got, want := history[0].Address, "Avenida Paulista, São Paulo - SP"; got != want {
		t.Fatalf("address=%q want %q", got, want)
	}
}

func TestHistorySkipsStationaryHeartbeatsButUpdatesCurrentPosition(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "history.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	now := time.Now().UTC()
	stamp := now.Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO users(id,name,created_at,updated_at) VALUES('u','User',?,?)`, stamp, stamp); err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.Exec(`INSERT INTO devices(id,user_id,name,token_hash,created_at) VALUES('d','u','Phone',?,?)`, []byte("test"), stamp); err != nil {
		t.Fatal(err)
	}
	service := New(db)
	for i, lat := range []float64{-23, -23.0001, -23.0002, -23.0012} {
		at := now.Add(time.Duration(i-4) * time.Second)
		if _, err = service.ReportMyLocation(ctx, "d", UserLocation{Latitude: lat, Longitude: -46, Accuracy: 10, OccurredAt: at}); err != nil {
			t.Fatal(err)
		}
		history, err := service.UserHistory(ctx, "u", 100)
		if err != nil {
			t.Fatal(err)
		}
		want := 1
		if i == 3 {
			want = 2
		}
		if len(history) != want {
			t.Fatalf("sample %d: got %d history points, want %d", i, len(history), want)
		}
		user, err := service.CurrentUser(ctx, "d")
		if err != nil {
			t.Fatal(err)
		}
		if user.LastLatitude == nil || *user.LastLatitude != lat || user.LastLocatedAt == nil || !user.LastLocatedAt.Equal(at) {
			t.Fatal("current position did not update")
		}
	}
}

func TestHistoryPreservesShortAreaCrossings(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "crossing.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	s := New(db)
	p, err := s.CreatePerson(ctx, Person{Name: "User"}, "d")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.CreatePlace(ctx, Place{Name: "Small area", Latitude: 0, Longitude: 0, RadiusMeters: 30}); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	for i, lat := range []float64{0, 0.00045} {
		transitions, err := s.Report(ctx, p.ID, "d", Location{Latitude: lat, Longitude: 0, Accuracy: 5, OccurredAt: now.Add(time.Duration(i-2) * time.Second)})
		if err != nil {
			t.Fatal(err)
		}
		if i == 1 && len(transitions) != 1 {
			t.Fatal("short exit lost")
		}
	}
	history, err := s.History(ctx, p.ID, 100)
	if err != nil || len(history) != 2 {
		t.Fatalf("crossing missing from history: %v %v", history, err)
	}
}
