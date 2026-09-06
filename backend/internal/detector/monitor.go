package detector

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/camera"
	"github.com/ferforastieri/valkyris/backend/internal/media"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
)

type CameraLister interface {
	List(context.Context) ([]camera.Camera, error)
	Get(context.Context, string) (camera.Camera, camera.Credentials, error)
}

type DetectionSubmitter interface {
	Submit(context.Context, rules.Detection) (any, error)
}

type Monitor struct {
	Cameras CameraLister
	Rules   interface {
		List(context.Context, string) ([]rules.Rule, error)
	}
	Media      *media.Manager
	ONVIF      *camera.ONVIFClient
	Classifier AudioClassifier
	DataDir    string
	Logger     *slog.Logger
	Submit     func(context.Context, rules.Detection) error

	mu      sync.Mutex
	running map[string]context.CancelFunc
}

func (m *Monitor) Run(ctx context.Context) {
	m.running = make(map[string]context.CancelFunc)
	m.refresh(ctx)
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	defer m.stopAll()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.refresh(ctx)
		}
	}
}

func (m *Monitor) refresh(ctx context.Context) {
	cameras, err := m.Cameras.List(ctx)
	if err != nil {
		m.Logger.Warn("refresh detector cameras", "error", err)
		return
	}
	active := make(map[string]bool, len(cameras))
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, cam := range cameras {
		if !cam.Enabled || cam.SetupStatus != "ready" {
			continue
		}
		active[cam.ID] = true
		if _, ok := m.running[cam.ID]; ok {
			continue
		}
		cameraContext, cancel := context.WithCancel(ctx)
		m.running[cam.ID] = cancel
		go m.monitorCamera(cameraContext, cam)
	}
	for id, cancel := range m.running {
		if !active[id] {
			cancel()
			delete(m.running, id)
		}
	}
}

func (m *Monitor) monitorCamera(ctx context.Context, cam camera.Camera) {
	if cam.Capabilities.Audio && m.Classifier != nil {
		go m.monitorAudio(ctx, cam.ID)
	}
	// Region rules require frames even when the camera supplies ONVIF events.
	go m.monitorVisual(ctx, cam.ID, !cam.Capabilities.Events)
	if !cam.Capabilities.Events || m.ONVIF == nil {
		<-ctx.Done()
		return
	}
	detailed, credentials, err := m.Cameras.Get(ctx, cam.ID)
	if err != nil {
		return
	}
	m.ONVIF.MonitorEvents(ctx, detailed, credentials, func(kind string, confidence float64) {
		m.submit(ctx, rules.Detection{CameraID: cam.ID, Type: kind, Confidence: confidence, OccurredAt: time.Now().UTC(), Metadata: map[string]any{"source": "onvif"}})
	})
}

func (m *Monitor) monitorAudio(ctx context.Context, cameraID string) {
	dir := filepath.Join(m.DataDir, "detector", cameraID)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		m.Logger.Warn("prepare detector directory", "camera", cameraID, "error", err)
		return
	}
	window := filepath.Join(dir, "audio.wav")
	for ctx.Err() == nil {
		captureCtx, cancel := context.WithTimeout(ctx, 18*time.Second)
		err := captureAudio(captureCtx, m.Media.RTSPURL(cameraID), window)
		cancel()
		if err == nil {
			results, classifyErr := m.Classifier.Classify(ctx, window)
			if classifyErr == nil {
				for _, result := range uniqueResults(results) {
					// The rule service applies the detector-specific reliability
					// threshold. Do not feed it noise from the classifier.
					if result.Confidence >= 0.70 {
						m.submit(ctx, rules.Detection{CameraID: cameraID, Type: result.Type, Confidence: result.Confidence, OccurredAt: time.Now().UTC(), Metadata: map[string]any{"source": "sherpa-onnx"}})
					}
				}
			} else if ctx.Err() == nil {
				m.Logger.Warn("classify audio", "camera", cameraID, "error", classifyErr)
			}
		} else if ctx.Err() == nil {
			m.Logger.Warn("capture detector audio", "camera", cameraID, "error", err)
		}
		if !wait(ctx, 2*time.Second) {
			return
		}
	}
}

func (m *Monitor) monitorVisual(ctx context.Context, cameraID string, fallback bool) {
	var previous []byte
	var previousAt time.Time
	for ctx.Err() == nil {
		var regionRules []rules.Rule
		if m.Rules != nil {
			all, err := m.Rules.List(ctx, cameraID)
			if err != nil {
				m.Logger.Warn("load visual rules", "camera", cameraID, "error", err)
				previous = nil
				if !wait(ctx, 2*time.Second) {
					return
				}
				continue
			}
			for _, r := range all {
				if r.Enabled && r.Motion != nil && rules.ActiveAt(r.Schedule, time.Now()) {
					regionRules = append(regionRules, r)
				}
			}
		}
		if !fallback && len(regionRules) == 0 {
			previous = nil
			if !wait(ctx, 2*time.Second) {
				return
			}
			continue
		}
		frame, err := m.Media.MonitoringFrame(ctx, cameraID)
		now := time.Now().UTC()
		if err == nil && len(previous) > 0 && now.Sub(previousAt) <= rules.MaxMotionSampleGap {
			if fallback {
				score, scoreErr := FrameDifference(previous, frame)
				if scoreErr == nil && score >= .12 {
					m.submit(ctx, rules.Detection{CameraID: cameraID, Type: "motion", Confidence: score, OccurredAt: now, Metadata: map[string]any{"source": "visual_fallback"}})
				}
			}
			for _, r := range regionRules {
				score, scoreErr := RegionDifference(previous, frame, r.Motion.Region)
				if scoreErr != nil {
					score = 0
				}
				m.submitRegion(ctx, cameraID, r, score, now)
			}
		} else {
			// Failed/missing frames never count as evidence of continuing motion.
			for _, r := range regionRules {
				m.submitRegion(ctx, cameraID, r, 0, now)
			}
		}
		if err == nil {
			previous = frame
			previousAt = now
		} else {
			previous = nil
		}
		if !wait(ctx, 2*time.Second) {
			return
		}
	}
}
func (m *Monitor) submitRegion(ctx context.Context, cameraID string, r rules.Rule, score float64, at time.Time) {
	m.submit(ctx, rules.Detection{CameraID: cameraID, Type: "motion", Confidence: score, OccurredAt: at,
		Motion:   &rules.MotionSample{RuleID: r.ID, RuleUpdatedAt: r.UpdatedAt, ChangedFraction: score},
		Metadata: map[string]any{"source": "visual_region", "changedFraction": score, "region": r.Motion.Region, "minDurationSeconds": r.Motion.MinDurationSeconds},
	})
}

func (m *Monitor) submit(ctx context.Context, detection rules.Detection) {
	if m.Submit != nil {
		if err := m.Submit(ctx, detection); err != nil && ctx.Err() == nil {
			m.Logger.Warn("submit detection", "camera", detection.CameraID, "type", detection.Type, "error", err)
		}
	}
}

func (m *Monitor) stopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, cancel := range m.running {
		cancel()
	}
}

func captureAudio(ctx context.Context, input, output string) error {
	cmd := exec.CommandContext(ctx, "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", input, "-t", "10", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-y", output)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("ffmpeg audio window: %w: %s", err, output)
	}
	return nil
}

func wait(ctx context.Context, duration time.Duration) bool {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
