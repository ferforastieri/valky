package detector

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

type AudioDecision struct {
	CameraID        string
	Start, End      time.Time
	Session         string
	Scores          map[string]float64
	RMS             float64
	InferenceMillis int64
	Accepted        bool
	Reason          string
}

type AudioAudit struct {
	Store    *store.Store
	mu       sync.Mutex
	prunedAt time.Time
}

func (a *AudioAudit) Record(ctx context.Context, d AudioDecision) error {
	scores, err := json.Marshal(d.Scores)
	if err != nil {
		return err
	}
	_, err = a.Store.DB.ExecContext(ctx, `INSERT INTO audio_decisions(camera_id,window_start,window_end,session_id,scores_json,rms,inference_ms,accepted,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)`, d.CameraID, d.Start.Format(time.RFC3339Nano), d.End.Format(time.RFC3339Nano), d.Session, string(scores), d.RMS, d.InferenceMillis, d.Accepted, d.Reason, time.Now().Unix())
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if time.Since(a.prunedAt) > 5*time.Minute {
		_, err = a.Store.DB.ExecContext(ctx, `DELETE FROM audio_decisions WHERE created_at<?`, time.Now().Add(-24*time.Hour).Unix())
		if err == nil {
			a.prunedAt = time.Now()
		}
	}
	return err
}

func (m *Monitor) recordAudio(ctx context.Context, d AudioDecision) {
	if m.AudioAudit == nil {
		return
	}
	if err := m.AudioAudit.Record(ctx, d); err != nil && ctx.Err() == nil {
		m.Logger.Warn("record audio decision", "camera", d.CameraID, "error", err)
	}
}
