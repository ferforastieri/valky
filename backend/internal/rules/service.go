package rules

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
)

var Catalog = []string{"motion", "person", "tamper", "baby_cry", "crying", "scream", "glass_break", "smoke_alarm", "fire_alarm", "siren", "doorbell", "knock", "dog_bark"}

type Service struct {
	store   *store.Store
	mu      sync.Mutex
	pending map[string]int
}

func NewService(s *store.Store) *Service { return &Service{store: s, pending: map[string]int{}} }

func (s *Service) Create(ctx context.Context, r Rule) (Rule, error) {
	r.Name = strings.TrimSpace(r.Name)
	r.DetectorTypes = canonicalStrings(r.DetectorTypes)
	r.Schedule.Days = canonicalInts(r.Schedule.Days)
	if r.CameraID == "" || r.Name == "" || len(r.DetectorTypes) == 0 {
		return r, fmt.Errorf("cameraId, name and detectorTypes are required")
	}
	if r.MinConfidence == 0 {
		r.MinConfidence = .65
	}
	if r.MinConfidence < 0 || r.MinConfidence > 1 {
		return r, fmt.Errorf("minConfidence must be between 0 and 1")
	}
	if r.Confirmations < 1 {
		r.Confirmations = 2
	}
	if r.CooldownSeconds < 1 {
		r.CooldownSeconds = 60
	}
	r.ID = uuid.NewString()
	r.Enabled = true
	r.CreatedAt = time.Now().UTC()
	r.UpdatedAt = r.CreatedAt
	det, _ := json.Marshal(r.DetectorTypes)
	schedule, _ := json.Marshal(r.Schedule)
	actions, _ := json.Marshal(r.Actions)
	result, err := s.store.DB.ExecContext(ctx, `INSERT INTO rules(id,camera_id,name,detector_types_json,min_confidence,confirmations,cooldown_seconds,schedule_json,actions_json,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING`, r.ID, r.CameraID, r.Name, string(det), r.MinConfidence, r.Confirmations, r.CooldownSeconds, string(schedule), string(actions), 1, r.CreatedAt.Format(time.RFC3339Nano), r.UpdatedAt.Format(time.RFC3339Nano))
	if err != nil {
		return r, err
	}
	inserted, err := result.RowsAffected()
	if err != nil {
		return r, err
	}
	if inserted > 0 {
		return r, nil
	}
	existing, err := scanRule(s.store.DB.QueryRowContext(ctx, `SELECT id,camera_id,name,detector_types_json,min_confidence,confirmations,cooldown_seconds,schedule_json,actions_json,enabled,last_triggered_at,created_at,updated_at FROM rules WHERE camera_id=? AND name=? AND detector_types_json=? AND min_confidence=? AND confirmations=? AND cooldown_seconds=? AND schedule_json=? AND actions_json=? LIMIT 1`, r.CameraID, r.Name, string(det), r.MinConfidence, r.Confirmations, r.CooldownSeconds, string(schedule), string(actions)))
	if err != nil {
		return r, fmt.Errorf("load existing idempotent rule: %w", err)
	}
	return existing, nil
}

func canonicalStrings(values []string) []string {
	canonical := make([]string, 0, len(values))
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			canonical = append(canonical, value)
		}
	}
	sort.Strings(canonical)
	return compactStrings(canonical)
}

func canonicalInts(values []int) []int {
	canonical := make([]int, 0, len(values))
	canonical = append(canonical, values...)
	sort.Ints(canonical)
	if len(canonical) == 0 {
		return canonical
	}
	out := canonical[:1]
	for _, value := range canonical[1:] {
		if value != out[len(out)-1] {
			out = append(out, value)
		}
	}
	return out
}

func compactStrings(values []string) []string {
	if len(values) == 0 {
		return values
	}
	out := values[:1]
	for _, value := range values[1:] {
		if value != out[len(out)-1] {
			out = append(out, value)
		}
	}
	return out
}

func (s *Service) List(ctx context.Context, cameraID string) ([]Rule, error) {
	query := `SELECT id,camera_id,name,detector_types_json,min_confidence,confirmations,cooldown_seconds,schedule_json,actions_json,enabled,last_triggered_at,created_at,updated_at FROM rules`
	var args []any
	if cameraID != "" {
		query += " WHERE camera_id=?"
		args = append(args, cameraID)
	}
	query += " ORDER BY name"
	rows, err := s.store.DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Rule, 0)
	for rows.Next() {
		r, e := scanRule(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Service) Delete(ctx context.Context, id string) error {
	result, err := s.store.DB.ExecContext(ctx, `DELETE FROM rules WHERE id=?`, id)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func (s *Service) Match(ctx context.Context, d Detection) ([]Rule, error) {
	all, err := s.List(ctx, d.CameraID)
	if err != nil {
		return nil, err
	}
	matched := make([]Rule, 0)
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range all {
		if !r.Enabled || d.Confidence < r.MinConfidence || !contains(r.DetectorTypes, d.Type) || !activeAt(r.Schedule, d.OccurredAt) {
			continue
		}
		if r.LastTriggeredAt != nil && d.OccurredAt.Sub(*r.LastTriggeredAt) < time.Duration(r.CooldownSeconds)*time.Second {
			continue
		}
		key := r.ID + ":" + d.Type
		s.pending[key]++
		if s.pending[key] < r.Confirmations {
			continue
		}
		s.pending[key] = 0
		now := d.OccurredAt.UTC()
		_, err = s.store.DB.ExecContext(ctx, `UPDATE rules SET last_triggered_at=?,updated_at=? WHERE id=?`, now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano), r.ID)
		if err != nil {
			return nil, err
		}
		r.LastTriggeredAt = &now
		matched = append(matched, r)
	}
	return matched, nil
}

type scanner interface{ Scan(...any) error }

func scanRule(row scanner) (Rule, error) {
	var r Rule
	var detector, schedule, actions, created, updated string
	var enabled int
	var last sql.NullString
	err := row.Scan(&r.ID, &r.CameraID, &r.Name, &detector, &r.MinConfidence, &r.Confirmations, &r.CooldownSeconds, &schedule, &actions, &enabled, &last, &created, &updated)
	if err != nil {
		return r, err
	}
	_ = json.Unmarshal([]byte(detector), &r.DetectorTypes)
	_ = json.Unmarshal([]byte(schedule), &r.Schedule)
	if r.Schedule.Days == nil {
		r.Schedule.Days = []int{}
	}
	_ = json.Unmarshal([]byte(actions), &r.Actions)
	r.Enabled = enabled == 1
	r.LastTriggeredAt = store.NullTime(last)
	r.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	r.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	return r, nil
}
func contains(values []string, target string) bool {
	for _, v := range values {
		if v == target {
			return true
		}
	}
	return false
}
func activeAt(s Schedule, at time.Time) bool {
	if len(s.Days) == 0 || s.Start == "" || s.End == "" {
		return true
	}
	loc := time.Local
	if s.Timezone != "" {
		if l, e := time.LoadLocation(s.Timezone); e == nil {
			loc = l
		}
	}
	local := at.In(loc)
	day := int(local.Weekday())
	if !containsInt(s.Days, day) {
		return false
	}
	parse := func(v string) int {
		p := strings.Split(v, ":")
		if len(p) != 2 {
			return 0
		}
		h, _ := strconv.Atoi(p[0])
		m, _ := strconv.Atoi(p[1])
		return h*60 + m
	}
	now := local.Hour()*60 + local.Minute()
	start, end := parse(s.Start), parse(s.End)
	if start <= end {
		return now >= start && now <= end
	}
	return now >= start || now <= end
}
func containsInt(values []int, target int) bool {
	for _, v := range values {
		if v == target {
			return true
		}
	}
	return false
}
