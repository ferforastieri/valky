package tracking

import (
	"context"
	"testing"
	"time"
)

func TestGeofenceDwellRejectsProductionSpikesAndConfirmsTravel(t *testing.T) {
	s, db := locationFixture(t)
	clock := time.Now().UTC()
	s.now = func() time.Time { return clock }
	report := func(seconds int, meters, accuracy float64, want int, entered bool) {
		t.Helper()
		clock = clock.Add(time.Duration(seconds) * time.Second)
		ts, err := s.ReportMyLocation(context.Background(), "d", UserLocation{Latitude: meters / 111195, Accuracy: accuracy, OccurredAt: clock})
		if err != nil || len(ts) != want || (want > 0 && ts[0].Entered != entered) {
			t.Fatalf("distance %.0f: %+v %v", meters, ts, err)
		}
	}
	report(0, 27, 9, 0, false)
	// Production Fernando: 298 m/14 m accuracy, back to 27 m after 81 seconds.
	report(60, 298, 14, 0, false)
	if n, err := s.PendingConfirmations(context.Background(), "d"); err != nil || n != 1 {
		t.Fatalf("pending=%d err=%v", n, err)
	}
	report(60, 298, 14, 0, false)
	report(21, 27, 9, 0, false)
	if n, err := s.PendingConfirmations(context.Background(), "d"); err != nil || n != 0 {
		t.Fatalf("pending after return=%d err=%v", n, err)
	}
	// Production Miriam: isolated 485 m/185 m, then an uncertain fix near home.
	report(60, 485, 185, 0, false)
	report(87, 68, 164, 0, false)
	report(60, 15, 61, 0, false)
	// Duplicates / provider bursts never satisfy the confirmation count.
	report(60, 500, 8, 0, false)
	report(0, 500, 8, 0, false)
	report(1, 500, 8, 0, false)
	// Long observation gaps start confirmation again.
	report(240, 500, 8, 0, false)
	report(60, 520, 8, 0, false)
	// Restart preserves pending confirmation, without prematurely emitting it.
	s = New(db)
	s.now = func() time.Time { return clock }
	report(60, 530, 8, 1, false)
	report(60, 540, 8, 0, false)
	report(60, 20, 8, 0, false)
	report(60, 20, 8, 0, false)
	report(60, 20, 8, 1, true)
	report(60, 20, 8, 0, false)
}
