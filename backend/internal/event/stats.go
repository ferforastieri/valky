package event

import (
	"context"
	"time"
)

type TypeCount struct {
	Type  string `json:"type"`
	Count int    `json:"count"`
}

type DayCount struct {
	Date  string `json:"date"`
	Count int    `json:"count"`
}

// CameraStats summarizes a camera's event history for the statistics screen.
type CameraStats struct {
	Days              int         `json:"days"`
	Total             int         `json:"total"`
	Unread            int         `json:"unread"`
	ByType            []TypeCount `json:"byType"`
	ByDay             []DayCount  `json:"byDay"`
	ByHour            []int       `json:"byHour"`
	AverageConfidence float64     `json:"averageConfidence"`
	WithClip          int         `json:"withClip"`
	WithSnapshot      int         `json:"withSnapshot"`
	FirstEventAt      string      `json:"firstEventAt,omitempty"`
	LastEventAt       string      `json:"lastEventAt,omitempty"`
}

// CameraStats aggregates the events of one camera over the last N days.
func (s *Service) CameraStats(ctx context.Context, cameraID string, days int) (CameraStats, error) {
	if days < 1 || days > 365 {
		days = 30
	}
	since := time.Now().UTC().Add(-time.Duration(days) * 24 * time.Hour).Format(time.RFC3339Nano)
	stats := CameraStats{Days: days, ByType: []TypeCount{}, ByDay: []DayCount{}, ByHour: make([]int, 24)}
	if err := s.store.DB.QueryRowContext(ctx,
		`SELECT count(*),
		        COALESCE(sum(acknowledged_at IS NULL),0),
		        COALESCE(avg(confidence),0),
		        COALESCE(sum(clip_path IS NOT NULL AND clip_path<>''),0),
		        COALESCE(sum(snapshot_path IS NOT NULL AND snapshot_path<>''),0),
		        COALESCE(min(occurred_at),''),
		        COALESCE(max(occurred_at),'')
		   FROM events WHERE camera_id=? AND occurred_at>=?`, cameraID, since,
	).Scan(&stats.Total, &stats.Unread, &stats.AverageConfidence, &stats.WithClip, &stats.WithSnapshot, &stats.FirstEventAt, &stats.LastEventAt); err != nil {
		return stats, err
	}

	rows, err := s.store.DB.QueryContext(ctx, `SELECT type, count(*) FROM events WHERE camera_id=? AND occurred_at>=? GROUP BY type ORDER BY count(*) DESC, type`, cameraID, since)
	if err != nil {
		return stats, err
	}
	for rows.Next() {
		var item TypeCount
		if err = rows.Scan(&item.Type, &item.Count); err != nil {
			rows.Close()
			return stats, err
		}
		stats.ByType = append(stats.ByType, item)
	}
	rows.Close()
	if err = rows.Err(); err != nil {
		return stats, err
	}

	rows, err = s.store.DB.QueryContext(ctx, `SELECT substr(occurred_at,1,10) AS day, count(*) FROM events WHERE camera_id=? AND occurred_at>=? GROUP BY day ORDER BY day`, cameraID, since)
	if err != nil {
		return stats, err
	}
	for rows.Next() {
		var item DayCount
		if err = rows.Scan(&item.Date, &item.Count); err != nil {
			rows.Close()
			return stats, err
		}
		stats.ByDay = append(stats.ByDay, item)
	}
	rows.Close()
	if err = rows.Err(); err != nil {
		return stats, err
	}

	rows, err = s.store.DB.QueryContext(ctx, `SELECT CAST(substr(occurred_at,12,2) AS INTEGER) AS hour, count(*) FROM events WHERE camera_id=? AND occurred_at>=? GROUP BY hour`, cameraID, since)
	if err != nil {
		return stats, err
	}
	for rows.Next() {
		var hour, count int
		if err = rows.Scan(&hour, &count); err != nil {
			rows.Close()
			return stats, err
		}
		if hour >= 0 && hour < 24 {
			stats.ByHour[hour] = count
		}
	}
	rows.Close()
	return stats, rows.Err()
}
