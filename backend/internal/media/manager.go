package media

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

type cachedFrame struct {
	data     []byte
	captured time.Time
}

type Manager struct {
	api        string
	hls        string
	playback   string
	recordings string
	http       *http.Client
	previewMu  sync.Mutex
	previews   map[string]cachedFrame
}

func New(api, hls, playback, recordings string) *Manager {
	return &Manager{
		api:        strings.TrimRight(api, "/"),
		hls:        strings.TrimRight(hls, "/"),
		playback:   strings.TrimRight(playback, "/"),
		recordings: recordings,
		http:       &http.Client{Timeout: 30 * time.Second},
		previews:   make(map[string]cachedFrame),
	}
}

var mediaPathID = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)

func (m *Manager) ConfigureCamera(ctx context.Context, id, rtspURI string) error {
	if !mediaPathID.MatchString(id) {
		return fmt.Errorf("invalid camera ID for media path")
	}
	if err := m.ensureInternalRTSP(ctx); err != nil {
		return fmt.Errorf("configure internal media transport: %w", err)
	}
	outputPath := "camera-" + id
	sourcePath := outputPath + "-source"

	// The app-facing path copies video without quality loss and normalizes the
	// camera audio to AAC, a codec supported by LL-HLS and Android Media3.
	output := map[string]any{
		"source":                "publisher",
		"record":                true,
		"recordPath":            "/data/recordings/%path/%Y-%m-%d_%H-%M-%S-%f",
		"recordFormat":          "fmp4",
		"recordPartDuration":    "1s",
		"recordSegmentDuration": "2s",
		"recordDeleteAfter":     "10m",
	}
	if err := m.upsertPath(ctx, outputPath, output, ""); err != nil {
		return fmt.Errorf("configure playable media path: %w", err)
	}

	transcode := fmt.Sprintf("ffmpeg -hide_banner -loglevel warning -fflags +genpts -use_wallclock_as_timestamps 1 -rtsp_transport tcp -i rtsp://127.0.0.1:8554/%s -map 0:v:0 -map 0:a:0? -c:v copy -c:a aac -profile:a aac_low -ar 48000 -ac 1 -b:a 64k -af aresample=async=1:first_pts=0 -muxdelay 0.1 -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/%s", sourcePath, outputPath)
	source := map[string]any{
		"source":            rtspURI,
		"sourceOnDemand":    false,
		"rtspTransport":     "tcp",
		"record":            false,
		"runOnReady":        transcode,
		"runOnReadyRestart": true,
	}
	if err := m.upsertPath(ctx, sourcePath, source, rtspURI); err != nil {
		return fmt.Errorf("configure camera media source: %w", err)
	}
	return nil
}

func (m *Manager) ensureInternalRTSP(ctx context.Context) error {
	payload, err := json.Marshal(map[string]any{
		"rtsp":            true,
		"rtspAddress":     ":8554",
		"rtspTransports":  []string{"tcp"},
		"hlsAlwaysRemux":  true,
		"playback":        true,
		"playbackAddress": ":9996",
	})
	if err != nil {
		return err
	}
	resp, body, err := m.configurePath(ctx, http.MethodPatch, m.api+"/v3/config/global/patch", payload)
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		return mediaMTXResponseError(resp.Status, body, "")
	}
	return nil
}

func (m *Manager) upsertPath(ctx context.Context, name string, settings map[string]any, secret string) error {
	payload, err := json.Marshal(settings)
	if err != nil {
		return fmt.Errorf("encode media path configuration: %w", err)
	}
	endpoint := m.api + "/v3/config/paths/add/" + url.PathEscape(name)
	resp, body, err := m.configurePath(ctx, http.MethodPost, endpoint, payload)
	if err != nil {
		return err
	}
	if resp.StatusCode == http.StatusConflict {
		resp, body, err = m.configurePath(ctx, http.MethodPatch, strings.Replace(endpoint, "/add/", "/patch/", 1), payload)
		if err != nil {
			return err
		}
	}
	if resp.StatusCode/100 != 2 {
		return mediaMTXResponseError(resp.Status, body, secret)
	}
	return nil
}

func (m *Manager) configurePath(ctx context.Context, method, endpoint string, payload []byte) (*http.Response, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(payload))
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := m.http.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 32<<10))
	if err != nil {
		return nil, nil, err
	}
	return resp, body, nil
}

func mediaMTXResponseError(status string, body []byte, rtspURI string) error {
	message := ""
	var response struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &response) == nil {
		message = strings.TrimSpace(response.Error)
	}
	if message == "" {
		message = strings.TrimSpace(string(body))
	}
	// A MediaMTX validation error can echo the source URL. Never propagate
	// camera credentials or RTSP addresses to persisted setup errors or logs.
	if rtspURI != "" && strings.Contains(message, rtspURI) {
		message = strings.ReplaceAll(message, rtspURI, "[RTSP source hidden]")
	}
	if strings.Contains(message, "rtsp://") || strings.Contains(message, "rtsps://") {
		message = "invalid RTSP source configuration"
	}
	if message == "" {
		return fmt.Errorf("MediaMTX returned %s", status)
	}
	return fmt.Errorf("MediaMTX returned %s: %s", status, message)
}

func (m *Manager) RemoveCamera(ctx context.Context, id string) error {
	if !mediaPathID.MatchString(id) {
		return fmt.Errorf("invalid camera ID for media path")
	}
	// Stop the raw source (and its FFmpeg hook) before removing the output.
	if err := m.removePath(ctx, "camera-"+id+"-source"); err != nil {
		return err
	}
	m.previewMu.Lock()
	delete(m.previews, id)
	m.previewMu.Unlock()
	return m.removePath(ctx, "camera-"+id)
}

func (m *Manager) removePath(ctx context.Context, name string) error {
	req, _ := http.NewRequestWithContext(ctx, http.MethodDelete, m.api+"/v3/config/paths/delete/"+url.PathEscape(name), nil)
	resp, err := m.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 && resp.StatusCode != 404 {
		return fmt.Errorf("MediaMTX returned %s", resp.Status)
	}
	return nil
}

func (m *Manager) HLSPath(id string) string { return "/camera-" + id + "/index.m3u8" }
func (m *Manager) HLSBase() string          { return m.hls }

func (m *Manager) CaptureSnapshot(ctx context.Context, rtspURI, output string) error {
	if err := os.MkdirAll(filepath.Dir(output), 0o700); err != nil {
		return err
	}
	cmd := exec.CommandContext(ctx, "ffmpeg", "-hide_banner", "-loglevel", "error", "-rtsp_transport", "tcp", "-i", rtspURI, "-frames:v", "1", "-y", output)
	return cmd.Run()
}

func (m *Manager) MonitoringFrame(ctx context.Context, cameraID string) ([]byte, error) {
	frameContext, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	input := m.hls + m.HLSPath(cameraID)
	cmd := exec.CommandContext(frameContext, "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", input, "-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "2", "pipe:1")
	frame, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("capture monitoring frame: %w", err)
	}
	return frame, nil
}

// PreviewFrame keeps a very short in-memory cache for UI navigation. Detection
// continues to call MonitoringFrame directly and therefore never sees stale
// frames.
func (m *Manager) PreviewFrame(ctx context.Context, cameraID string) ([]byte, error) {
	m.previewMu.Lock()
	cached, ok := m.previews[cameraID]
	if ok && time.Since(cached.captured) < 5*time.Second {
		frame := append([]byte(nil), cached.data...)
		m.previewMu.Unlock()
		return frame, nil
	}
	m.previewMu.Unlock()

	frame, err := m.MonitoringFrame(ctx, cameraID)
	if err != nil {
		return nil, err
	}
	m.previewMu.Lock()
	m.previews[cameraID] = cachedFrame{data: append([]byte(nil), frame...), captured: time.Now()}
	m.previewMu.Unlock()
	return frame, nil
}

// MaterializeClip joins the rolling fMP4 fragments intersecting the event window.
func (m *Manager) MaterializeClip(ctx context.Context, cameraID string, occurred time.Time, output string) error {
	return m.MaterializeClipWindow(ctx, cameraID, occurred.Add(-5*time.Second), occurred.Add(10*time.Second), output)
}

// MaterializeRecentClip extracts an on-demand clip from the rolling camera
// buffer. It does not start another RTSP consumer or wait for a new recording.
func (m *Manager) MaterializeRecentClip(ctx context.Context, cameraID string, duration time.Duration, output string) error {
	if duration <= 0 || duration > time.Minute {
		return fmt.Errorf("recent clip duration must be between 0 and 60 seconds")
	}
	if !mediaPathID.MatchString(cameraID) {
		return fmt.Errorf("invalid camera ID for media path")
	}
	// Keep a small distance from the live edge: MediaMTX can only serve parts
	// that have already been finalized. Its playback server assembles those
	// fMP4 fragments into a standard MP4, avoiding fragile filesystem concat.
	end := time.Now().UTC().Add(-3 * time.Second)
	query := url.Values{
		"path":     {"camera-" + cameraID},
		"start":    {end.Add(-duration).Format(time.RFC3339Nano)},
		"duration": {fmt.Sprintf("%.3f", duration.Seconds())},
		"format":   {"mp4"},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, m.playback+"/get?"+query.Encode(), nil)
	if err != nil {
		return err
	}
	resp, err := m.http.Do(req)
	if err != nil {
		return fmt.Errorf("request MediaMTX playback: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<10))
		return mediaMTXResponseError(resp.Status, body, "")
	}
	file, err := os.OpenFile(output, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(file, resp.Body)
	closeErr := file.Close()
	if copyErr != nil {
		return fmt.Errorf("write recent clip: %w", copyErr)
	}
	if closeErr != nil {
		return closeErr
	}
	return nil
}

// MaterializeClipWindow joins all rolling fragments covering a possibly
// extended event window. This allows detections that overlap to share one clip.
func (m *Manager) MaterializeClipWindow(ctx context.Context, cameraID string, from, to time.Time, output string) error {
	return m.materializeClipWindow(ctx, cameraID, from, to, output)
}

func (m *Manager) materializeClipWindow(ctx context.Context, cameraID string, from, to time.Time, output string) error {
	dir := filepath.Join(m.recordings, "camera-"+cameraID)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	var files []string
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, e := entry.Info()
		if e == nil && info.ModTime().After(from.Add(-3*time.Second)) && info.ModTime().Before(to.Add(3*time.Second)) {
			files = append(files, filepath.Join(dir, entry.Name()))
		}
	}
	if len(files) == 0 {
		return fmt.Errorf("no media fragments cover event window")
	}
	sort.Strings(files)
	if err = os.MkdirAll(filepath.Dir(output), 0o700); err != nil {
		return err
	}
	list := output + ".concat"
	var content strings.Builder
	for _, file := range files {
		content.WriteString("file '")
		content.WriteString(strings.ReplaceAll(file, "'", "'\\''"))
		content.WriteString("'\n")
	}
	if err = os.WriteFile(list, []byte(content.String()), 0o600); err != nil {
		return err
	}
	defer os.Remove(list)
	duration := to.Sub(from)
	if duration <= 0 {
		return fmt.Errorf("invalid clip window")
	}
	args := []string{"-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list, "-t", fmt.Sprintf("%.3f", duration.Seconds()), "-c", "copy", "-y", output}
	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	return cmd.Run()
}
