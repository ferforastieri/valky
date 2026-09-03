package event

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
)

type Event struct {
	ID             string         `json:"id"`
	CameraID       string         `json:"cameraId"`
	RuleID         *string        `json:"ruleId,omitempty"`
	Type           string         `json:"type"`
	Confidence     float64        `json:"confidence"`
	OccurredAt     time.Time      `json:"occurredAt"`
	SnapshotPath   string         `json:"snapshotPath,omitempty"`
	ClipPath       string         `json:"clipPath,omitempty"`
	Metadata       map[string]any `json:"metadata"`
	AcknowledgedAt *time.Time     `json:"acknowledgedAt,omitempty"`
	AcknowledgedBy string         `json:"acknowledgedBy,omitempty"`
	CreatedAt      time.Time      `json:"createdAt"`
}
type Service struct{ store *store.Store }

func NewService(s *store.Store) *Service { return &Service{store: s} }
func (s *Service) Create(ctx context.Context, e Event) (Event, error) {
	e.ID = uuid.NewString()
	e.CreatedAt = time.Now().UTC()
	if e.OccurredAt.IsZero() {
		e.OccurredAt = e.CreatedAt
	}
	meta, _ := json.Marshal(e.Metadata)
	var rule any
	if e.RuleID != nil {
		rule = *e.RuleID
	}
	_, err := s.store.DB.ExecContext(ctx, `INSERT INTO events(id,camera_id,rule_id,type,confidence,occurred_at,snapshot_path,clip_path,metadata_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)`, e.ID, e.CameraID, rule, e.Type, e.Confidence, e.OccurredAt.Format(time.RFC3339Nano), nullable(e.SnapshotPath), nullable(e.ClipPath), string(meta), e.CreatedAt.Format(time.RFC3339Nano))
	return e, err
}
func (s *Service) SetMedia(ctx context.Context, id, snapshot, clip string) error {
	_, err := s.store.DB.ExecContext(ctx, `UPDATE events SET snapshot_path=?,clip_path=? WHERE id=?`, nullable(snapshot), nullable(clip), id)
	return err
}
func (s *Service) SetSnapshot(ctx context.Context, id, snapshot string) error {
	_, err := s.store.DB.ExecContext(ctx, `UPDATE events SET snapshot_path=? WHERE id=?`, nullable(snapshot), id)
	return err
}
func (s *Service) SetClip(ctx context.Context, id, clip string) error {
	_, err := s.store.DB.ExecContext(ctx, `UPDATE events SET clip_path=? WHERE id=?`, nullable(clip), id)
	return err
}
func (s *Service) List(ctx context.Context, cameraID string, limit int) ([]Event, error) {
	if limit < 1 || limit > 200 {
		limit = 50
	}
	q := `SELECT id,camera_id,rule_id,type,confidence,occurred_at,snapshot_path,clip_path,metadata_json,acknowledged_at,acknowledged_by,created_at FROM events`
	var args []any
	if cameraID != "" {
		q += " WHERE camera_id=?"
		args = append(args, cameraID)
	}
	q += " ORDER BY occurred_at DESC LIMIT ?"
	args = append(args, limit)
	rows, err := s.store.DB.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Event, 0)
	for rows.Next() {
		e, er := scan(rows)
		if er != nil {
			return nil, er
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
func (s *Service) Get(ctx context.Context, id string) (Event, error) {
	return scan(s.store.DB.QueryRowContext(ctx, `SELECT id,camera_id,rule_id,type,confidence,occurred_at,snapshot_path,clip_path,metadata_json,acknowledged_at,acknowledged_by,created_at FROM events WHERE id=?`, id))
}
func (s *Service) Acknowledge(ctx context.Context, id, device string) error {
	result, err := s.store.DB.ExecContext(ctx, `UPDATE events SET acknowledged_at=?,acknowledged_by=? WHERE id=?`, time.Now().UTC().Format(time.RFC3339Nano), device, id)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

type scanner interface{ Scan(...any) error }

func scan(row scanner) (Event, error) {
	var e Event
	var rule, snapshot, clip, ack, ackBy sql.NullString
	var occurred, created, meta string
	err := row.Scan(&e.ID, &e.CameraID, &rule, &e.Type, &e.Confidence, &occurred, &snapshot, &clip, &meta, &ack, &ackBy, &created)
	if err != nil {
		return e, err
	}
	if rule.Valid {
		e.RuleID = &rule.String
	}
	e.SnapshotPath = snapshot.String
	e.ClipPath = clip.String
	e.AcknowledgedAt = store.NullTime(ack)
	e.AcknowledgedBy = ackBy.String
	e.OccurredAt, _ = time.Parse(time.RFC3339Nano, occurred)
	e.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	_ = json.Unmarshal([]byte(meta), &e.Metadata)
	return e, nil
}
func nullable(value string) any {
	if value == "" {
		return nil
	}
	return value
}
