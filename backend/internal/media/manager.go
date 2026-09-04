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
	"sort"
	"strings"
	"time"
)

type Manager struct {
	api        string
	hls        string
	recordings string
	http       *http.Client
}

func New(api, hls, recordings string) *Manager {
	return &Manager{api: strings.TrimRight(api, "/"), hls: strings.TrimRight(hls, "/"), recordings: recordings, http: &http.Client{Timeout: 8 * time.Second}}
}

func (m *Manager) ConfigureCamera(ctx context.Context, id, rtspURI string) error {
	// MediaMTX requires recordPath to contain %path. For a path named
	// camera-<id>, this still materializes under /data/recordings/camera-<id>,
	// which is the directory consumed by MaterializeClipWindow.
	payload, err := json.Marshal(map[string]any{"source": rtspURI, "sourceOnDemand": false, "record": true, "recordPath": "/data/recordings/%path/%Y-%m-%d_%H-%M-%S-%f", "recordFormat": "fmp4", "recordPartDuration": "1s", "recordSegmentDuration": "2s", "recordDeleteAfter": "10m"})
	if err != nil {
		return fmt.Errorf("encode media path configuration: %w", err)
	}
	endpoint := m.api + "/v3/config/paths/add/" + url.PathEscape("camera-"+id)
	resp, body, err := m.configurePath(ctx, http.MethodPost, endpoint, payload)
	if err != nil {
		return fmt.Errorf("configure media path: %w", err)
	}
	if resp.StatusCode == http.StatusConflict {
		resp, body, err = m.configurePath(ctx, http.MethodPatch, strings.Replace(endpoint, "/add/", "/patch/", 1), payload)
		if err != nil {
			return fmt.Errorf("update media path: %w", err)
		}
	}
	if resp.StatusCode/100 != 2 {
		return mediaMTXResponseError(resp.Status, body, rtspURI)
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
	req, _ := http.NewRequestWithContext(ctx, http.MethodDelete, m.api+"/v3/config/paths/delete/"+url.PathEscape("camera-"+id), nil)
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
	cmd := exec.CommandContext(frameContext, "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", input, "-frames:v", "1", "-vf", "scale=320:-2", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
	frame, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("capture monitoring frame: %w", err)
	}
	return frame, nil
}

// MaterializeClip joins the rolling fMP4 fragments intersecting the event window.
func (m *Manager) MaterializeClip(ctx context.Context, cameraID string, occurred time.Time, output string) error {
	return m.MaterializeClipWindow(ctx, cameraID, occurred.Add(-5*time.Second), occurred.Add(10*time.Second), output)
}

// MaterializeClipWindow joins all rolling fragments covering a possibly
// extended event window. This allows detections that overlap to share one clip.
func (m *Manager) MaterializeClipWindow(ctx context.Context, cameraID string, from, to time.Time, output string) error {
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
	cmd := exec.CommandContext(ctx, "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list, "-t", fmt.Sprintf("%.3f", duration.Seconds()), "-c", "copy", "-y", output)
	return cmd.Run()
}
