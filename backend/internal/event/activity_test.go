package event

import (
	"context"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestActivityCountsAndIntervalPagination(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/events.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := NewService(db)
	ctx := context.Background()
	now := time.Date(2026, 9, 7, 20, 0, 0, 0, time.UTC)
	// More records than either client's recent-event list; include another timezone.
	for i := 0; i < 251; i++ {
		_, err := s.Create(ctx, Event{Source: "camera", Type: "motion", OccurredAt: now.Add(-30 * time.Minute).In(time.FixedZone("local", -3*3600))})
		if err != nil {
			t.Fatal(err)
		}
	}
	// Location records must be excluded before aggregation and pagination.
	for i := 0; i < 150; i++ {
		for _, e := range []Event{{Source: "tracking", Type: "place_entered"}, {Source: "tracking", Type: "place_exited"}, {Source: "camera", Type: "place_entered"}} {
			e.OccurredAt = now.Add(-15 * time.Minute)
			if _, err := s.Create(ctx, e); err != nil {
				t.Fatal(err)
			}
		}
	}
	for _, hours := range []int{12, 24, 36, 48} {
		buckets, err := s.Activity(ctx, hours, now)
		if err != nil {
			t.Fatal(err)
		}
		if len(buckets) != 12 || buckets[11].Count != 251 || !buckets[0].Start.Equal(now.Add(-time.Duration(hours)*time.Hour)) {
			t.Fatalf("invalid %dh buckets: %+v", hours, buckets)
		}
	}
	seen := map[string]bool{}
	for offset, want := range map[int]int{0: 100, 100: 100, 200: 51, 300: 0} {
		page, err := s.ListInterval(ctx, now.Add(-time.Hour), now, offset)
		if err != nil || len(page) != want {
			t.Fatalf("page %d: len=%d err=%v", offset, len(page), err)
		}
		for _, e := range page {
			if seen[e.ID] {
				t.Fatal("duplicate event across pages")
			}
			seen[e.ID] = true
		}
	}
}

func TestActivityHalfOpenBoundaries(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/events.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := NewService(db)
	ctx := context.Background()
	now := time.Date(2026, 9, 7, 20, 0, 0, 0, time.UTC)
	for _, hours := range []int{12, 24, 36, 48} {
		if _, err := db.DB.Exec("DELETE FROM events"); err != nil {
			t.Fatal(err)
		}
		start := now.Add(-time.Duration(hours) * time.Hour)
		width := time.Duration(hours) * time.Hour / 12
		for _, at := range []time.Time{start.Add(-time.Second), start, start.Add(width - time.Second), start.Add(width), now.Add(-time.Second), now} {
			if _, err := s.Create(ctx, Event{Type: "motion", OccurredAt: at}); err != nil {
				t.Fatal(err)
			}
		}
		buckets, err := s.Activity(ctx, hours, now)
		if err != nil {
			t.Fatal(err)
		}
		for i, b := range buckets {
			want := 0
			if i == 0 {
				want = 2
			}
			if i == 1 || i == 11 {
				want = 1
			}
			events, err := s.ListInterval(ctx, b.Start, b.End, 0)
			if err != nil || b.Count != want || len(events) != want {
				t.Fatalf("%dh bucket %d count=%d details=%d want=%d err=%v", hours, i, b.Count, len(events), want, err)
			}
		}
	}
}
