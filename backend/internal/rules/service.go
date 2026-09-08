package rules

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata"

	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
)

var Catalog = []string{"motion", "person", "tamper", "baby_cry", "crying", "scream", "glass_break", "smoke_alarm", "fire_alarm", "siren", "doorbell", "knock", "dog_bark"}

type Service struct {
	store         *store.Store
	mu            sync.Mutex
	pending       map[string]int
	audioPending  map[string]audioConfirmation
	motionPending map[string]motionWindow
}

func NewService(s *store.Store) *Service {
	return &Service{store: s, pending: map[string]int{}, audioPending: map[string]audioConfirmation{}, motionPending: map[string]motionWindow{}}
}

func (s *Service) Create(ctx context.Context, r Rule) (Rule, error) {
	if err := normalizeRule(&r); err != nil {
		return r, err
	}
	r.ID = uuid.NewString()
	r.Enabled = true
	r.CreatedAt = time.Now().UTC()
	r.UpdatedAt = r.CreatedAt
	det, _ := json.Marshal(r.DetectorTypes)
	schedule, _ := json.Marshal(r.Schedule)
	actions, _ := json.Marshal(r.Actions)
	motion, _ := json.Marshal(r.Motion)
	result, err := s.store.DB.ExecContext(ctx, `INSERT INTO rules(id,camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json,motion_json,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING`, r.ID, r.CameraID, r.Name, string(det), r.Confirmations, r.CooldownSeconds, string(schedule), string(actions), string(motion), 1, r.CreatedAt.Format(time.RFC3339Nano), r.UpdatedAt.Format(time.RFC3339Nano))
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
	existing, err := scanRule(s.store.DB.QueryRowContext(ctx, `SELECT id,camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json,motion_json,enabled,last_triggered_at,created_at,updated_at FROM rules WHERE camera_id=? AND name=? AND detector_types_json=? AND confirmations=? AND cooldown_seconds=? AND schedule_json=? AND actions_json=? AND motion_json=? LIMIT 1`, r.CameraID, r.Name, string(det), r.Confirmations, r.CooldownSeconds, string(schedule), string(actions), string(motion)))
	if err != nil {
		return r, fmt.Errorf("load existing idempotent rule: %w", err)
	}
	return existing, nil
}

func (s *Service) Update(ctx context.Context, id string, r Rule) (Rule, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := normalizeRule(&r); err != nil {
		return r, err
	}
	r.ID = id
	// Rules have no paused state in the product. A stale client payload must
	// never silently turn an edited rule off.
	r.Enabled = true
	r.UpdatedAt = time.Now().UTC()
	det, _ := json.Marshal(r.DetectorTypes)
	schedule, _ := json.Marshal(r.Schedule)
	actions, _ := json.Marshal(r.Actions)
	motion, _ := json.Marshal(r.Motion)
	result, err := s.store.DB.ExecContext(ctx, `UPDATE rules SET camera_id=?,name=?,detector_types_json=?,confirmations=?,cooldown_seconds=?,schedule_json=?,actions_json=?,motion_json=?,enabled=?,updated_at=? WHERE id=?`,
		r.CameraID, r.Name, string(det), r.Confirmations, r.CooldownSeconds, string(schedule), string(actions), string(motion), 1, r.UpdatedAt.Format(time.RFC3339Nano), id)
	if err != nil {
		return r, err
	}
	if affected, _ := result.RowsAffected(); affected == 0 {
		return r, sql.ErrNoRows
	}
	return scanRule(s.store.DB.QueryRowContext(ctx, `SELECT id,camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json,motion_json,enabled,last_triggered_at,created_at,updated_at FROM rules WHERE id=?`, id))
}

func normalizeRule(r *Rule) error {
	r.Name = strings.TrimSpace(r.Name)
	r.DetectorTypes = canonicalStrings(r.DetectorTypes)
	if err := validateSchedule(&r.Schedule); err != nil {
		return err
	}
	if r.CooldownSeconds == 0 {
		r.CooldownSeconds = 60
	}
	if r.CooldownSeconds < 10 || r.CooldownSeconds > 3600 {
		return fmt.Errorf("cooldownSeconds must be between 10 and 3600")
	}
	if r.Motion != nil {
		if len(r.DetectorTypes) != 1 || r.DetectorTypes[0] != "motion" {
			return fmt.Errorf("region settings require only the motion detector")
		}
		m := r.Motion
		if !validRegion(m.Region) || m.MinDurationSeconds < 2 || m.MinDurationSeconds > 300 || math.IsNaN(m.MinChangedFraction) || math.IsInf(m.MinChangedFraction, 0) || m.MinChangedFraction < .01 || m.MinChangedFraction > .5 {
			return fmt.Errorf("invalid motion region, duration (2–300s) or changed fraction (0.01–0.5)")
		}
	}
	if r.CameraID == "" || r.Name == "" || len(r.DetectorTypes) == 0 {
		return fmt.Errorf("cameraId, name and detectorTypes are required")
	}
	if r.Confirmations < 1 {
		r.Confirmations = 1
	}
	return nil
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
	query := `SELECT id,camera_id,name,detector_types_json,confirmations,cooldown_seconds,schedule_json,actions_json,motion_json,enabled,last_triggered_at,created_at,updated_at FROM rules`
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
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.motionPending, id)
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
	if d.Motion == nil && d.Audio == nil && !reliableDetection(d) {
		return nil, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	all, err := s.List(ctx, d.CameraID)
	if err != nil {
		return nil, err
	}
	matched := make([]Rule, 0)
	for _, r := range all {
		if d.Motion != nil && (r.ID != d.Motion.RuleID || r.Motion == nil) {
			continue
		}
		if d.Motion == nil && r.Motion != nil {
			continue
		}
		if !r.Enabled || !contains(r.DetectorTypes, d.Type) || !activeAt(r.Schedule, d.OccurredAt) {
			delete(s.motionPending, r.ID)
			delete(s.audioPending, r.ID+":"+d.Type)
			continue
		}
		if d.Audio != nil && !d.Audio.Start.Before(d.Audio.End) {
			continue
		}
		if r.LastTriggeredAt != nil && d.OccurredAt.Sub(*r.LastTriggeredAt) < time.Duration(r.CooldownSeconds)*time.Second {
			delete(s.motionPending, r.ID)
			delete(s.audioPending, r.ID+":"+d.Type)
			continue
		}
		if r.Motion != nil && !s.persistentMotion(r, d) {
			continue
		}
		key := r.ID + ":" + d.Type
		if d.Audio != nil {
			if !s.confirmAudio(key, r, d) {
				continue
			}
		} else {
			s.pending[key]++
		}
		if d.Audio == nil && r.Motion == nil && s.pending[key] < r.Confirmations {
			continue
		}
		s.pending[key] = 0
		delete(s.motionPending, r.ID)
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

// Confidence is an acquisition safeguard, not a user-tuned rule setting.
// Keep the event value for inspection but reject weak detector output before
// it can increment confirmations or create an alert.
func reliableDetection(d Detection) bool {
	source, _ := d.Metadata["source"].(string)
	if source == "onvif" {
		return d.Confidence >= .5
	}
	minimum := AudioThreshold(d.Type)
	return d.Confidence >= minimum
}

type scanner interface{ Scan(...any) error }

func scanRule(row scanner) (Rule, error) {
	var r Rule
	var detector, schedule, actions, motion, created, updated string
	var enabled int
	var last sql.NullString
	err := row.Scan(&r.ID, &r.CameraID, &r.Name, &detector, &r.Confirmations, &r.CooldownSeconds, &schedule, &actions, &motion, &enabled, &last, &created, &updated)
	if err != nil {
		return r, err
	}
	_ = json.Unmarshal([]byte(detector), &r.DetectorTypes)
	_ = json.Unmarshal([]byte(schedule), &r.Schedule)
	if r.Schedule.Days == nil {
		r.Schedule.Days = []int{}
	}
	_ = json.Unmarshal([]byte(actions), &r.Actions)
	_ = json.Unmarshal([]byte(motion), &r.Motion)
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
	if s.Start == "" && s.End == "" && len(s.Days) == 0 {
		return true
	}
	loc, err := time.LoadLocation(s.Timezone)
	if err != nil {
		return false
	}
	local := at.In(loc)
	now := local.Hour()*60 + local.Minute()
	start, ok := clockMinutes(s.Start)
	if !ok {
		return false
	}
	end, ok := clockMinutes(s.End)
	if !ok {
		return false
	}
	day := int(local.Weekday())
	if start > end {
		if now < end {
			day = (day + 6) % 7
		} else if now < start {
			return false
		}
	} else if now < start || now >= end {
		return false
	}
	return containsInt(s.Days, day)
}

// ActiveAt is shared with the frame worker to avoid capture outside a schedule.
func ActiveAt(s Schedule, at time.Time) bool { return activeAt(s, at) }
func clockMinutes(v string) (int, bool) {
	if len(v) != 5 || v[2] != ':' {
		return 0, false
	}
	for _, i := range []int{0, 1, 3, 4} {
		if v[i] < '0' || v[i] > '9' {
			return 0, false
		}
	}
	h, e1 := strconv.Atoi(v[:2])
	m, e2 := strconv.Atoi(v[3:])
	return h*60 + m, e1 == nil && e2 == nil && h >= 0 && h < 24 && m >= 0 && m < 60
}
func validateSchedule(s *Schedule) error {
	s.Days = canonicalInts(s.Days)
	if s.Start == "" && s.End == "" && len(s.Days) == 0 {
		s.Timezone = ""
		return nil
	}
	_, startOK := clockMinutes(s.Start)
	_, endOK := clockMinutes(s.End)
	if !startOK || !endOK || s.Start == s.End || len(s.Days) == 0 {
		return fmt.Errorf("schedule requires days and distinct HH:mm start/end")
	}
	for _, d := range s.Days {
		if d < 0 || d > 6 {
			return fmt.Errorf("schedule days must be 0–6")
		}
	}
	if s.Timezone == "" {
		return fmt.Errorf("schedule timezone is required")
	}
	if _, err := time.LoadLocation(s.Timezone); err != nil {
		return fmt.Errorf("invalid schedule timezone")
	}
	return nil
}
func validRegion(r Region) bool {
	for _, v := range []float64{r.X, r.Y, r.Width, r.Height} {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return false
		}
	}
	return r.X >= 0 && r.Y >= 0 && r.Width >= .05 && r.Height >= .05 && r.X+r.Width <= 1.000001 && r.Y+r.Height <= 1.000001
}

const MaxMotionSampleGap = 12 * time.Second

type motionWindow struct{ start, last, revision time.Time }

func (s *Service) persistentMotion(r Rule, d Detection) bool {
	sample := d.Motion
	if sample == nil || d.Type != "motion" || !sample.RuleUpdatedAt.Equal(r.UpdatedAt) || math.IsNaN(sample.ChangedFraction) || math.IsInf(sample.ChangedFraction, 0) || sample.ChangedFraction < r.Motion.MinChangedFraction || sample.ChangedFraction > 1 {
		delete(s.motionPending, r.ID)
		return false
	}
	w := s.motionPending[r.ID]
	if !w.last.IsZero() && !d.OccurredAt.After(w.last) {
		return false
	}
	if w.start.IsZero() || d.OccurredAt.Sub(w.last) > MaxMotionSampleGap || !w.revision.Equal(r.UpdatedAt) {
		w = motionWindow{start: d.OccurredAt, revision: r.UpdatedAt}
	}
	w.last = d.OccurredAt
	s.motionPending[r.ID] = w
	return d.OccurredAt.Sub(w.start) >= time.Duration(r.Motion.MinDurationSeconds)*time.Second
}
func containsInt(values []int, target int) bool {
	for _, v := range values {
		if v == target {
			return true
		}
	}
	return false
}
