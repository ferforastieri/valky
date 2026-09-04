package media

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestConfigureCameraUsesMediaMTXPathPlaceholder(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v3/config/paths/add/camera-abc" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatal(err)
		}
		var payload map[string]any
		if err = json.Unmarshal(body, &payload); err != nil {
			t.Fatal(err)
		}
		if payload["recordPath"] != "/data/recordings/%path/%Y-%m-%d_%H-%M-%S-%f" {
			t.Fatalf("invalid recordPath: %v", payload["recordPath"])
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := New(server.URL, "http://media", "/data/recordings")
	if err := manager.ConfigureCamera(context.Background(), "abc", "rtsp://camera.local/stream1"); err != nil {
		t.Fatal(err)
	}
}

func TestConfigureCameraIncludesSafeMediaMTXError(t *testing.T) {
	const source = "rtsp://user:secret@camera.local/stream1"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"invalid source ` + source + `"}`))
	}))
	defer server.Close()

	err := New(server.URL, "http://media", "/data/recordings").ConfigureCamera(context.Background(), "abc", source)
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
