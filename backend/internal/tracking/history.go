package tracking

import (
	"context"
	"math"
	"time"
)

const historyAccuracy = 50.0
const historyDistance = 200.0

func (s *Service) UserHistory(ctx context.Context, userID string, limit int) ([]UserLocation, error) {
	return s.UserHistoryPage(ctx, userID, limit, 0)
}

// Consolidate before pagination, including records written by older versions.
// Only consecutive nearby observations merge: returning after a journey stays visible.
func (s *Service) UserHistoryPage(ctx context.Context, userID string, limit, offset int) ([]UserLocation, error) {
	return s.UserHistoryBefore(ctx, userID, limit, offset, time.Time{})
}

func (s *Service) UserHistoryBefore(ctx context.Context, userID string, limit, offset int, until time.Time) ([]UserLocation, error) {
	if limit < 1 || limit > 100 {
		limit = 20
	}
	query := `SELECT id,user_id,latitude,longitude,accuracy,address,occurred_at,last_seen_at FROM user_locations WHERE user_id=? AND accuracy>0 AND accuracy<=?`
	args := []any{userID, historyAccuracy}
	if !until.IsZero() {
		query += " AND julianday(occurred_at)<=julianday(?)"
		args = append(args, until.Format(time.RFC3339Nano))
	}
	query += " ORDER BY occurred_at DESC,id DESC"
	rows, err := s.store.DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]UserLocation, 0, limit)
	var current *UserLocation
	skipped := 0
	emit := func() bool {
		if current == nil {
			return false
		}
		if skipped < offset {
			skipped++
			return false
		}
		out = append(out, *current)
		return len(out) == limit
	}
	for rows.Next() {
		var p UserLocation
		var occurred, last string
		if err := rows.Scan(&p.ID, &p.UserID, &p.Latitude, &p.Longitude, &p.Accuracy, &p.Address, &occurred, &last); err != nil {
			return nil, err
		}
		p.OccurredAt, err = time.Parse(time.RFC3339Nano, occurred)
		if err != nil {
			return nil, err
		}
		p.LastSeenAt = p.OccurredAt
		if last != "" {
			if at, e := time.Parse(time.RFC3339Nano, last); e == nil && at.After(p.LastSeenAt) {
				p.LastSeenAt = at
			}
		}
		if !until.IsZero() && p.LastSeenAt.After(until) {
			p.LastSeenAt = until
		}
		if current != nil && distanceMeters(current.Latitude, current.Longitude, p.Latitude, p.Longitude) < math.Max(historyDistance, 2*(current.Accuracy+p.Accuracy)) {
			current.OccurredAt = p.OccurredAt
			continue
		}
		if emit() {
			return out, nil
		}
		current = &p
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	emit()
	return out, nil
}
