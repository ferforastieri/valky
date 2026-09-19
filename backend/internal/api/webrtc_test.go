package api

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/camera"
	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/media"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestWHEPProxyAuthenticatesAndRewritesSessionLocation(t *testing.T) {
	t.Run("mobile", func(t *testing.T) { testWHEPProxy(t, false) })
	t.Run("browser cookie and compatible stream", func(t *testing.T) { testWHEPProxy(t, true) })
}
func testWHEPProxy(t *testing.T, browser bool) {
	var requests []string
	mediaServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Fatal("application bearer token was forwarded to MediaMTX")
		}
		if browser && r.Method == http.MethodGet {
			w.WriteHeader(http.StatusOK)
			return
		}
		requests = append(requests, r.Method+" "+r.URL.Path)
		switch r.Method {
		case http.MethodPost:
			if got := r.Header.Get("Content-Type"); got != "application/sdp" {
				t.Fatalf("unexpected WHEP content type: %q", got)
			}
			if body, _ := io.ReadAll(r.Body); string(body) != "offer-sdp" {
				t.Fatalf("unexpected offer: %q", body)
			}
			w.Header().Set("Content-Type", "application/sdp")
			w.Header().Set("Location", "/camera-camera-1/whep/abc-123")
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte("answer-sdp"))
		case http.MethodPatch:
			w.WriteHeader(http.StatusNoContent)
		default:
			t.Fatalf("unexpected WHEP method: %s", r.Method)
		}
	}))
	defer mediaServer.Close()

	directory := t.TempDir()
	database, err := store.Open(filepath.Join(directory, "valkyris.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })
	vault, err := appcrypto.LoadOrCreate(filepath.Join(directory, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	repository := camera.NewRepository(database, vault)
	created, err := repository.Create(context.Background(), camera.CreateInput{
		Name: "Entrada", Host: "192.0.2.102", Port: 2020, Username: "camera", Password: "secret", RTSPURI: "rtsp://camera/stream1",
	}, camera.Capabilities{}, "", camera.ServiceAddresses{})
	if err != nil {
		t.Fatal(err)
	}
	manager := auth.NewManager(database, 10*time.Minute)
	session, err := manager.BootstrapAdmin(context.Background(), auth.LoginRequest{Username: "admin", Password: "home password 123", DeviceName: "phone"})
	if err != nil {
		t.Fatal(err)
	}
	if browser {
		session, err = manager.Login(context.Background(), auth.LoginRequest{Username: "admin", Password: "home password 123", DeviceName: "Browser", ReadOnly: true})
		if err != nil {
			t.Fatal(err)
		}
	}
	authenticate := func(r *http.Request) {
		if browser {
			r.AddCookie(&http.Cookie{Name: auth.ViewerCookie, Value: session.Token})
			r.Header.Set("X-Valkyris-Viewer", "1")
			r.Header.Set("Origin", "https://example.com")
		} else {
			r.Header.Set("Authorization", "Bearer "+session.Token)
		}
	}
	suffix := ""
	mediaSuffix := ""
	if browser {
		suffix = "?profile=browser"
		mediaSuffix = "-browser"
	}
	server := NewServer(manager, repository, nil, media.New(mediaServer.URL, "rtsp://media", mediaServer.URL, mediaServer.URL, filepath.Join(directory, "recordings")), nil, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	handler := server.Handler()

	post := httptest.NewRequest(http.MethodPost, "/api/v1/cameras/"+created.ID+"/live/webrtc/whep"+suffix, bytes.NewBufferString("offer-sdp"))
	authenticate(post)
	post.Header.Set("Content-Type", "application/sdp")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, post)
	if response.Code != http.StatusCreated || response.Body.String() != "answer-sdp" {
		t.Fatalf("unexpected WHEP response: %d %q", response.Code, response.Body.String())
	}
	location := "/api/v1/cameras/" + created.ID + "/live/webrtc/whep/abc-123" + suffix
	if response.Header().Get("Location") != location {
		t.Fatalf("session location was not rewritten: %q", response.Header().Get("Location"))
	}

	patch := httptest.NewRequest(http.MethodPatch, location, bytes.NewBufferString("candidate"))
	authenticate(patch)
	patch.Header.Set("Content-Type", "application/trickle-ice-sdpfrag")
	patchResponse := httptest.NewRecorder()
	handler.ServeHTTP(patchResponse, patch)
	if patchResponse.Code != http.StatusNoContent {
		t.Fatalf("unexpected PATCH response: %d", patchResponse.Code)
	}
	if want := []string{"POST /camera-" + created.ID + mediaSuffix + "/whep", "PATCH /camera-" + created.ID + mediaSuffix + "/whep/abc-123"}; len(requests) != len(want) || requests[0] != want[0] || requests[1] != want[1] {
		t.Fatalf("unexpected MediaMTX requests: %#v", requests)
	}
}
