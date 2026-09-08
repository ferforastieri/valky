package tracking

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"sync"
	"testing"
	"time"
)

func locationFixture(t *testing.T) (*Service, *store.Store) {
	t.Helper()
	db, err := store.Open(t.TempDir() + "/locations.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	stamp := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO users(id,name,created_at,updated_at) VALUES('u','Miriam',?,?)`, stamp, stamp); err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.Exec(`INSERT INTO devices(id,user_id,name,token_hash,created_at) VALUES('d','u','Phone',x'01',?)`, stamp); err != nil {
		t.Fatal(err)
	}
	s := New(db)
	if _, err = s.CreatePlace(context.Background(), Place{Name: "Home", Latitude: 0, Longitude: 0, RadiusMeters: 200}); err != nil {
		t.Fatal(err)
	}
	return s, db
}
func TestRejectRepeatedAndOutOfOrderLocations(t *testing.T) {
	s, db := locationFixture(t)
	at := time.Now().UTC().Add(-time.Minute)
	first := UserLocation{Latitude: 0, Longitude: 0, Accuracy: 8, OccurredAt: at}
	outside := UserLocation{Latitude: .00291, Longitude: 0, Accuracy: 152, OccurredAt: at.Add(11 * time.Second)}
	for i := 0; i < 12; i++ {
		for _, l := range []UserLocation{first, outside} {
			transitions, err := s.ReportMyLocation(context.Background(), "d", l)
			if err != nil || len(transitions) != 0 {
				t.Fatalf("spurious transition %+v %v", transitions, err)
			}
		}
	}
	var count int
	db.DB.QueryRow(`SELECT count(*) FROM user_locations`).Scan(&count)
	if count != 1 {
		t.Fatalf("stored %d duplicate locations", count)
	}
	latest, err := s.CurrentUser(context.Background(), "d")
	if err != nil || !latest.LastLocatedAt.Equal(outside.OccurredAt) {
		t.Fatalf("latest regressed: %+v %v", latest, err)
	}
	// A distant observation must persist before it creates an exit.
	for i := 0; i < 3; i++ {
		clock := at.Add(time.Duration(40+i*60) * time.Second)
		s.now = func() time.Time { return clock }
		transitions, err := s.ReportMyLocation(context.Background(), "d", UserLocation{Latitude: .01, Accuracy: 8, OccurredAt: clock})
		want := 0
		if i == 2 {
			want = 1
		}
		if err != nil || len(transitions) != want || (want == 1 && transitions[0].Entered) {
			t.Fatalf("sample %d: %+v %v", i, transitions, err)
		}
	}

}
func TestStaleAndConcurrentLocations(t *testing.T) {
	s, db := locationFixture(t)
	old := UserLocation{Accuracy: 8, OccurredAt: time.Now().Add(-10 * time.Minute)}
	if ts, err := s.ReportMyLocation(context.Background(), "d", old); err != nil || len(ts) != 0 {
		t.Fatal(ts, err)
	}
	l := UserLocation{Accuracy: 8, OccurredAt: time.Now().Add(-time.Second)}
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.ReportMyLocation(context.Background(), "d", l); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	var count int
	db.DB.QueryRow(`SELECT count(*) FROM user_locations`).Scan(&count)
	if count != 1 {
		t.Fatalf("stored %d records", count)
	}
}
func TestAccuracyBoundaryHysteresis(t *testing.T) {
	p := Place{Latitude: 0, Longitude: 0, RadiusMeters: 200}
	for _, tc := range []struct {
		lat, accuracy   float64
		inside, certain bool
	}{{0, 100, true, true}, {.00291, 152, false, false}, {.0018, 5, false, false}, {.01, 8, false, true}, {0, 500, false, false}, {0, 0, false, false}} {
		inside, certain := confidentMembership(tc.lat, 0, tc.accuracy, p)
		if inside != tc.inside || certain != tc.certain {
			t.Fatalf("%+v => %v %v", tc, inside, certain)
		}
	}
}
