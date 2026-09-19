package media

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestBrowserStreamIsSharedOnDemandAndUsesExistingSource(t *testing.T) {
	exists := false
	creates := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			if !exists {
				w.WriteHeader(404)
			}
		case http.MethodPatch:
			w.WriteHeader(404)
		case http.MethodPost:
			creates++
			var settings map[string]any
			if err := json.NewDecoder(r.Body).Decode(&settings); err != nil {
				t.Fatal(err)
			}
			command, _ := settings["runOnDemand"].(string)
			for _, want := range []string{"-i rtsp://127.0.0.1:8554/camera-abc ", "-c:v libx264", "-profile:v baseline", "-bf 0", "-c:a copy", "camera-abc-browser"} {
				if !strings.Contains(command, want) {
					t.Errorf("missing %q: %s", want, command)
				}
			}
			if settings["record"] != false || settings["runOnDemandCloseAfter"] != "10s" {
				t.Errorf("unexpected settings: %v", settings)
			}
			exists = true
		default:
			t.Errorf("unexpected request: %s", r.Method)
		}
	}))
	defer server.Close()
	m := New(server.URL, "rtsp://media", "http://media", "http://media", t.TempDir())
	for range 2 {
		if err := m.ConfigureBrowserLive(context.Background(), "abc"); err != nil {
			t.Fatal(err)
		}
	}
	if creates != 1 {
		t.Fatalf("recreated active stream %d times", creates)
	}
	if err := m.ConfigureBrowserLive(context.Background(), "../unsafe"); err == nil {
		t.Fatal("accepted invalid id")
	}
}
