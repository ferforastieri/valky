package tracking

import (
	"context"
	"fmt"
	"testing"
	"time"
)

func TestHistoryConsolidatesBeforePaginationAndPreservesReturns(t *testing.T) {
	s, db := locationFixture(t)
	ctx := context.Background()
	start := time.Now().Add(-time.Hour).UTC()
	// A, jitter near A, inaccurate jump, B, jitter near B, return to A.
	for i, p := range []struct{ lat, accuracy float64 }{{0, 8}, {.0001, 10}, {.1, 150}, {.01, 8}, {.0101, 12}, {0, 9}} {
		at := start.Add(time.Duration(i) * time.Minute).Format(time.RFC3339Nano)
		if _, err := db.DB.Exec(`INSERT INTO user_locations(id,user_id,latitude,longitude,accuracy,address,occurred_at,created_at) VALUES(?,'u',?,0,?,'',?,?)`, fmt.Sprint(i), p.lat, p.accuracy, at, at); err != nil {
			t.Fatal(err)
		}
	}
	all, err := s.UserHistoryPage(ctx, "u", 20, 0)
	if err != nil || len(all) != 3 {
		t.Fatalf("timeline=%+v err=%v", all, err)
	}
	if all[0].Latitude != 0 || all[2].OccurredAt != start || !all[2].LastSeenAt.Equal(start.Add(time.Minute)) {
		t.Fatalf("return or stationary interval lost: %+v", all)
	}
	for offset, want := range []string{"5", "4", "1"} {
		page, err := s.UserHistoryPage(ctx, "u", 1, offset)
		if err != nil || len(page) != 1 || page[0].ID != want {
			t.Fatalf("offset %d: %+v %v", offset, page, err)
		}
	}
	page, err := s.UserHistoryPage(ctx, "u", 1, 3)
	if err != nil || len(page) != 0 {
		t.Fatal(page, err)
	}
	// New reports must not shift older pages in an already-open timeline.
	at := start.Add(time.Hour).Format(time.RFC3339Nano)
	if _, err := db.DB.Exec(`INSERT INTO user_locations(id,user_id,latitude,longitude,accuracy,address,occurred_at,created_at) VALUES('new','u',1,0,8,'',?,?)`, at, at); err != nil {
		t.Fatal(err)
	}
	page, err = s.UserHistoryBefore(ctx, "u", 1, 1, all[0].LastSeenAt)
	if err != nil || len(page) != 1 || page[0].ID != "4" {
		t.Fatalf("snapshot shifted: %+v %v", page, err)
	}

}

func TestNearbyConfirmationsExtendHistoryWithoutDuplicate(t *testing.T) {
	s, db := locationFixture(t)
	ctx := context.Background()
	start := time.Now().UTC()
	for i, lat := range []float64{0, .0001, .0002, .0001} {
		at := start.Add(time.Duration(i) * time.Minute)
		s.now = func() time.Time { return at }
		if _, err := s.ReportMyLocation(ctx, "d", UserLocation{Latitude: lat, Accuracy: 8, OccurredAt: at, Address: "Untrusted street"}); err != nil {
			t.Fatal(err)
		}
	}
	var count int
	db.DB.QueryRow(`SELECT count(*) FROM user_locations`).Scan(&count)
	if count != 1 {
		t.Fatalf("stored %d nearby points", count)
	}
	history, err := s.UserHistory(ctx, "u", 20)
	if err != nil || len(history) != 1 || history[0].Address != "" || !history[0].LastSeenAt.Equal(start.Add(3*time.Minute)) {
		t.Fatal(history, err)
	}
}
