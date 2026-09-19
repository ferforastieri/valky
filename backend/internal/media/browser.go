package media

import (
	"context"
	"fmt"
	"io"
	"net/http"
)

// ConfigureBrowserLive creates a shared, on-demand H264 baseline stream for
// browsers unable to decode the camera's original H265 / H264 profile.
// It reads the existing local stream, never another connection to the camera.
func (m *Manager) ConfigureBrowserLive(ctx context.Context, id string) error {
	if !mediaPathID.MatchString(id) {
		return fmt.Errorf("invalid camera ID for media path")
	}
	m.browserMu.Lock()
	defer m.browserMu.Unlock()
	name := "camera-" + id + "-browser"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, m.api+"/v3/config/paths/get/"+name, nil)
	if err != nil {
		return err
	}
	resp, err := m.http.Do(req)
	if err != nil {
		return err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 32<<10))
	resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	if resp.StatusCode != http.StatusNotFound {
		return fmt.Errorf("read browser media path: %s", resp.Status)
	}
	command := fmt.Sprintf("ffmpeg -hide_banner -loglevel warning -rtsp_transport tcp -i rtsp://127.0.0.1:8554/camera-%s -map 0:v:0 -map 0:a:0? -vf scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2 -r 15 -c:v libx264 -threads 2 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p -bf 0 -g 30 -b:v 1500k -maxrate 2000k -bufsize 2000k -c:a copy -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/%s", id, name)
	return m.upsertPath(ctx, name, map[string]any{
		"source": "publisher", "record": false,
		"runOnDemand": command, "runOnDemandRestart": true,
		"runOnDemandStartTimeout": "20s", "runOnDemandCloseAfter": "10s",
	}, "")
}
