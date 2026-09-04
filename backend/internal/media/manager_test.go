package media

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestMaterializeRecentClipRejectsInvalidDuration(t *testing.T) {
	manager := New("http://media", "http://media", "http://media", t.TempDir())
	for _, duration := range []time.Duration{0, -time.Second, 61 * time.Second} {
		if err := manager.MaterializeRecentClip(context.Background(), "camera", duration, "clip.mp4"); err == nil {
			t.Fatalf("expected duration %s to be rejected", duration)
		}
	}
}

func TestConfigureCameraUsesMediaMTXPathPlaceholder(t *testing.T) {
	const cameraSource = "rtsp://user:secret@camera.local/stream1"
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatal(err)
		}
		var payload map[string]any
		if err = json.Unmarshal(body, &payload); err != nil {
			t.Fatal(err)
		}
		switch r.URL.Path {
		case "/v3/config/global/patch":
			if r.Method != http.MethodPatch || payload["rtsp"] != true || payload["hlsAlwaysRemux"] != true || payload["playback"] != true {
				t.Fatalf("invalid global transport configuration: %v", payload)
			}
		case "/v3/config/paths/add/camera-abc":
			if r.Method != http.MethodPost {
				t.Fatalf("unexpected method for output path: %s", r.Method)
			}
			if payload["source"] != "publisher" || payload["record"] != true || payload["recordPath"] != "/data/recordings/%path/%Y-%m-%d_%H-%M-%S-%f" {
				t.Fatalf("invalid output path: %v", payload)
			}
		case "/v3/config/paths/add/camera-abc-source":
			if r.Method != http.MethodPost {
				t.Fatalf("unexpected method for source path: %s", r.Method)
			}
			command, _ := payload["runOnReady"].(string)
			if payload["source"] != cameraSource || payload["record"] != false || payload["rtspTransport"] != "tcp" {
				t.Fatalf("invalid source path: %v", payload)
			}
			for _, expected := range []string{"rtsp://127.0.0.1:8554/camera-abc-source", "-c:v copy", "-c:a aac", "aresample=async=1:first_pts=0", "rtsp://127.0.0.1:8554/camera-abc"} {
				if !strings.Contains(command, expected) {
					t.Fatalf("transcoder command does not contain %q: %s", expected, command)
				}
			}
			if strings.Contains(command, "secret") || strings.Contains(command, "camera.local") {
				t.Fatalf("transcoder command leaked camera source: %s", command)
			}
		default:
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := New(server.URL, "http://media", "http://playback", "/data/recordings")
	if err := manager.ConfigureCamera(context.Background(), "abc", cameraSource); err != nil {
		t.Fatal(err)
	}
	if requests != 3 {
		t.Fatalf("expected global transport, output and source paths, got %d requests", requests)
	}
}

func TestMaterializeRecentClipUsesPlaybackServer(t *testing.T) {
	const content = "standard mp4"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/get" || r.URL.Query().Get("path") != "camera-abc" || r.URL.Query().Get("duration") != "10.000" || r.URL.Query().Get("format") != "mp4" {
			t.Fatalf("unexpected playback request: %s", r.URL.String())
		}
		if _, err := time.Parse(time.RFC3339Nano, r.URL.Query().Get("start")); err != nil {
			t.Fatalf("invalid playback start: %v", err)
		}
		_, _ = io.WriteString(w, content)
	}))
	defer server.Close()
	output := t.TempDir() + "/recent.mp4"
	manager := New("http://api", "http://hls", server.URL, t.TempDir())
	if err := manager.MaterializeRecentClip(context.Background(), "abc", 10*time.Second, output); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(output)
	if err != nil || string(got) != content {
		t.Fatalf("unexpected clip: %q err=%v", got, err)
	}
}

func TestConfigureCameraIncludesSafeMediaMTXError(t *testing.T) {
	const source = "rtsp://user:secret@camera.local/stream1"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "camera-abc-source") {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"invalid source ` + source + `"}`))
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	err := New(server.URL, "http://media", "http://playback", "/data/recordings").ConfigureCamera(context.Background(), "abc", source)
	if err == nil {
		t.Fatal("expected MediaMTX error")
	}
	message := err.Error()
	if strings.Contains(message, "secret") || strings.Contains(message, "rtsp://") {
		t.Fatalf("MediaMTX error leaked source credentials: %s", message)
	}
	if !strings.Contains(message, "400 Bad Request") {
		t.Fatalf("MediaMTX status is missing: %s", message)
	}
}

func TestRemoveCameraDeletesSourceBeforeOutput(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	if err := New(server.URL, "", "", t.TempDir()).RemoveCamera(context.Background(), "abc"); err != nil {
		t.Fatal(err)
	}
	want := []string{"/v3/config/paths/delete/camera-abc-source", "/v3/config/paths/delete/camera-abc"}
	if len(paths) != len(want) || paths[0] != want[0] || paths[1] != want[1] {
		t.Fatalf("unexpected deletion order: %v", paths)
	}
}
