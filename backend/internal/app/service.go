package app

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/api"
	"github.com/ferforastieri/valkyris/backend/internal/camera"
	"github.com/ferforastieri/valkyris/backend/internal/event"
	"github.com/ferforastieri/valkyris/backend/internal/media"
	"github.com/ferforastieri/valkyris/backend/internal/notify"
	"github.com/ferforastieri/valkyris/backend/internal/preferences"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
)

type Service struct {
	Rules         *rules.Service
	Events        *event.Service
	Cameras       *camera.Repository
	Media         *media.Manager
	Notify        *notify.Service
	Hub           *api.Hub
	DataDir       string
	Logger        *slog.Logger
	Preferences   *preferences.Service
	captureMu     sync.Mutex
	captureGroups map[string]*captureGroup
}

type captureGroup struct {
	cameraID string
	from     time.Time
	until    time.Time
	eventIDs []string
	output   string
}

func (s *Service) Submit(ctx context.Context, d rules.Detection) ([]event.Event, error) {
	matched, err := s.Rules.Match(ctx, d)
	if err != nil {
		return nil, err
	}
	created := make([]event.Event, 0, len(matched))
	for _, rule := range matched {
		if d.Metadata == nil {
			d.Metadata = map[string]any{}
		}
		d.Metadata["alarm"] = rule.Actions.Alarm
		ruleID := rule.ID
		e, err := s.Events.Create(ctx, event.Event{CameraID: d.CameraID, RuleID: &ruleID, Type: d.Type, Confidence: d.Confidence, OccurredAt: d.OccurredAt, Metadata: d.Metadata})
		if err != nil {
			return created, err
		}
		created = append(created, e)
		s.Hub.Broadcast(map[string]any{"type": "event.created", "event": e})
		if rule.Actions.Notify || rule.Actions.Alarm {
			if err := s.Notify.Enqueue(ctx, e); err != nil {
				s.Logger.Error("enqueue event notification", "event", e.ID, "error", err)
			}
		}
		if rule.Actions.Record {
			s.queueCapture(e)
		}
	}
	return created, nil
}
func (s *Service) queueCapture(e event.Event) {
	go s.captureSnapshot(e)
	s.captureMu.Lock()
	if s.captureGroups == nil {
		s.captureGroups = make(map[string]*captureGroup)
	}
	if group := s.captureGroups[e.CameraID]; group != nil && !e.OccurredAt.After(group.until) {
		group.eventIDs = append(group.eventIDs, e.ID)
		if candidate := e.OccurredAt.Add(-5 * time.Second); candidate.Before(group.from) {
			group.from = candidate
		}
		if candidate := e.OccurredAt.Add(10 * time.Second); candidate.After(group.until) {
			group.until = candidate
		}
		s.captureMu.Unlock()
		return
	}
	group := &captureGroup{
		cameraID: e.CameraID,
		from:     e.OccurredAt.Add(-5 * time.Second),
		until:    e.OccurredAt.Add(10 * time.Second),
		eventIDs: []string{e.ID},
		output:   filepath.Join(s.DataDir, "events", e.ID, "clip.mp4"),
	}
	s.captureGroups[e.CameraID] = group
	s.captureMu.Unlock()
	go s.captureClip(group)
}

func (s *Service) captureSnapshot(e event.Event) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_, cred, err := s.Cameras.Get(ctx, e.CameraID)
	if err != nil {
		return
	}
	dir := filepath.Join(s.DataDir, "events", e.ID)
	snapshot := filepath.Join(dir, "snapshot.jpg")
	if err = s.Media.CaptureSnapshot(ctx, cred.RTSPURI, snapshot); err != nil {
		s.Logger.Warn("snapshot capture failed", "event", e.ID, "error", err)
		return
	}
	_ = s.Events.SetSnapshot(context.Background(), e.ID, snapshot)
	s.Hub.Broadcast(map[string]any{"type": "event.media_ready", "eventId": e.ID})
}

func (s *Service) captureClip(group *captureGroup) {
	for {
		s.captureMu.Lock()
		until := group.until
		s.captureMu.Unlock()
		if wait := time.Until(until.Add(time.Second)); wait > 0 {
			timer := time.NewTimer(wait)
			<-timer.C
		}
		s.captureMu.Lock()
		if group.until.After(until) {
			s.captureMu.Unlock()
			continue
		}
		ids := append([]string(nil), group.eventIDs...)
		if s.captureGroups[group.cameraID] == group {
			delete(s.captureGroups, group.cameraID)
		}
		s.captureMu.Unlock()

		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		err := s.Media.MaterializeClipWindow(ctx, group.cameraID, group.from, group.until, group.output)
		cancel()
		if err != nil {
			s.Logger.Warn("clip materialization failed", "event", ids[0], "error", err)
			return
		}
		for _, id := range ids {
			_ = s.Events.SetClip(context.Background(), id, group.output)
			s.Hub.Broadcast(map[string]any{"type": "event.media_ready", "eventId": id})
		}
		return
	}
}

func (s *Service) RunRetention(ctx context.Context, maxAge time.Duration, maxBytes int64) {
	ticker := time.NewTicker(time.Hour)
	defer ticker.Stop()
	s.retainConfigured(ctx, maxAge, maxBytes)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.retainConfigured(ctx, maxAge, maxBytes)
		}
	}
}

func (s *Service) retainConfigured(ctx context.Context, defaultAge time.Duration, defaultBytes int64) {
	maxAge, maxBytes := defaultAge, defaultBytes
	if s.Preferences != nil {
		value, err := s.Preferences.Retention(ctx)
		if err != nil {
			s.Logger.Warn("load media retention settings", "error", err)
		} else {
			maxAge = time.Duration(value.MaxAgeDays) * 24 * time.Hour
			maxBytes = value.MaxStorageGB * 1024 * 1024 * 1024
		}
	}
	s.retain(maxAge, maxBytes)
}
func (s *Service) retain(maxAge time.Duration, maxBytes int64) {
	root := filepath.Join(s.DataDir, "events")
	type file struct {
		path string
		size int64
		mod  time.Time
	}
	var files []file
	var total int64
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		info, e := d.Info()
		if e == nil {
			files = append(files, file{path: path, size: info.Size(), mod: info.ModTime()})
			total += info.Size()
		}
		return nil
	})
	sort.Slice(files, func(i, j int) bool { return files[i].mod.Before(files[j].mod) })
	cutoff := time.Now().Add(-maxAge)
	for _, f := range files {
		if f.mod.Before(cutoff) || total > maxBytes {
			if os.Remove(f.path) == nil {
				total -= f.size
			}
		}
	}
}
