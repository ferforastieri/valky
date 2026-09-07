package event

import (
	"context"
	"time"
)

type ActivityBucket struct {
	Start time.Time `json:"start"`
	End   time.Time `json:"end"`
	Count int       `json:"count"`
}

// Activity and interval details share the same database predicate.
const activityEvents = "source != 'tracking' AND type NOT IN ('place_entered','place_exited')"

// Activity uses twelve equal intervals and excludes location events.
func (s *Service) Activity(ctx context.Context, hours int, now time.Time) ([]ActivityBucket, error) {
	end := now.UTC().Truncate(time.Second)
	width := time.Duration(hours) * time.Hour / 12
	start := end.Add(-time.Duration(hours) * time.Hour)
	out := make([]ActivityBucket, 12)
	for i := range out {
		out[i] = ActivityBucket{Start: start.Add(time.Duration(i) * width), End: start.Add(time.Duration(i+1) * width)}
	}
	rows, err := s.store.DB.QueryContext(ctx, `SELECT CAST((unixepoch(occurred_at)-?)/? AS INTEGER),COUNT(*) FROM events WHERE `+activityEvents+` AND unixepoch(occurred_at)>=? AND unixepoch(occurred_at)<? GROUP BY 1`, start.Unix(), int64(width/time.Second), start.Unix(), end.Unix())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var i, count int
		if err := rows.Scan(&i, &count); err != nil {
			return nil, err
		}
		out[i].Count = count
	}
	return out, rows.Err()
}
