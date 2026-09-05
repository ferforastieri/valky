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
	Cameras    CameraLister
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
	if !cam.Capabilities.Events {
		m.monitorVisual(ctx, cam.ID)
		return
	}
	if m.ONVIF == nil {
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
		err := captureAudio(captureCtx, m.Media.HLSBase()+m.Media.HLSPath(cameraID), window)
		cancel()
		if err == nil {
			results, classifyErr := m.Classifier.Classify(ctx, window)
			if classifyErr == nil {
				for _, result := range uniqueResults(results) {
					if result.Confidence >= 0.05 {
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

func (m *Monitor) monitorVisual(ctx context.Context, cameraID string) {
	var previous []byte
	for ctx.Err() == nil {
		frame, err := m.Media.MonitoringFrame(ctx, cameraID)
		if err == nil && len(previous) > 0 {
			score, scoreErr := FrameDifference(previous, frame)
			if scoreErr == nil && score >= 0.12 {
				m.submit(ctx, rules.Detection{CameraID: cameraID, Type: "motion", Confidence: score, OccurredAt: time.Now().UTC(), Metadata: map[string]any{"source": "visual_fallback"}})
			}
		}
		if err == nil {
			previous = frame
		}
		if !wait(ctx, 2*time.Second) {
			return
		}
	}
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
