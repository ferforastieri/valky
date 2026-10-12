package preferences

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/camera"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

const retentionKey = "media_retention"
const locationAlertsKey = "location_alerts"

type Retention struct {
	MaxAgeDays   int   `json:"maxAgeDays"`
	MaxStorageGB int64 `json:"maxStorageGB"`
}

type Service struct {
	store    *store.Store
	defaults Retention
}

func New(store *store.Store, defaults Retention) *Service {
	return &Service{store: store, defaults: defaults}
}

func (s *Service) Retention(ctx context.Context) (Retention, error) {
	var raw string
	err := s.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key=?`, retentionKey).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return s.defaults, nil
	}
	if err != nil {
		return Retention{}, fmt.Errorf("load media retention: %w", err)
	}
	var value Retention
	if err = json.Unmarshal([]byte(raw), &value); err != nil {
		return Retention{}, fmt.Errorf("decode media retention: %w", err)
	}
	if err = validate(value); err != nil {
		return Retention{}, err
	}
	return value, nil
}

func (s *Service) SetRetention(ctx context.Context, value Retention) (Retention, error) {
	if err := validate(value); err != nil {
		return Retention{}, err
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return Retention{}, fmt.Errorf("encode media retention: %w", err)
	}
	_, err = s.store.DB.ExecContext(ctx, `
		INSERT INTO settings(key,value,updated_at) VALUES(?,?,?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at`,
		retentionKey, string(raw), time.Now().UTC().Format(time.RFC3339Nano),
	)
	if err != nil {
		return Retention{}, fmt.Errorf("save media retention: %w", err)
	}
	return value, nil
}

// LocationAlerts is the single presentation used for location and battery
// notifications; every family member receives it the same way.
func (s *Service) LocationAlerts(ctx context.Context) (camera.AlertPresentation, error) {
	var raw string
	err := s.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key=?`, locationAlertsKey).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return camera.DefaultAlertPresentation(), nil
	}
	if err != nil {
		return camera.AlertPresentation{}, fmt.Errorf("load location alerts: %w", err)
	}
	var value camera.AlertPresentation
	if err = json.Unmarshal([]byte(raw), &value); err != nil {
		return camera.AlertPresentation{}, fmt.Errorf("decode location alerts: %w", err)
	}
	if err = value.Validate(); err != nil {
		return camera.AlertPresentation{}, err
	}
	return value, nil
}

func (s *Service) SetLocationAlerts(ctx context.Context, value camera.AlertPresentation) (camera.AlertPresentation, error) {
	if err := value.Validate(); err != nil {
		return camera.AlertPresentation{}, err
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return camera.AlertPresentation{}, fmt.Errorf("encode location alerts: %w", err)
	}
	_, err = s.store.DB.ExecContext(ctx, `
		INSERT INTO settings(key,value,updated_at) VALUES(?,?,?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at`,
		locationAlertsKey, string(raw), time.Now().UTC().Format(time.RFC3339Nano),
	)
	if err != nil {
		return camera.AlertPresentation{}, fmt.Errorf("save location alerts: %w", err)
	}
	return value, nil
}

func validate(value Retention) error {
	if value.MaxAgeDays < 1 || value.MaxAgeDays > 365 {
		return fmt.Errorf("media retention days must be between 1 and 365")
	}
	if value.MaxStorageGB < 1 || value.MaxStorageGB > 1000 {
		return fmt.Errorf("media retention storage must be between 1 and 1000 GB")
	}
	return nil
}
