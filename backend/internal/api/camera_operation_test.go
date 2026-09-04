package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/camera"
	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/media"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestCameraCreationContinuesAsynchronously(t *testing.T) {
	probeCompleted := make(chan struct{})
	var onvifServer *httptest.Server
	onvifServer = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		switch {
		case strings.Contains(string(body), "GetCapabilities"):
			time.Sleep(350 * time.Millisecond)
			close(probeCompleted)
			fmt.Fprintf(w, `<s:Envelope><s:Body><GetCapabilitiesResponse><Capabilities><Media><tt:XAddr>%s/onvif/media</tt:XAddr></Media></Capabilities></GetCapabilitiesResponse></s:Body></s:Envelope>`, onvifServer.URL)
		case strings.Contains(string(body), "GetProfiles"):
			fmt.Fprint(w, `<s:Envelope><s:Body><GetProfilesResponse><Profiles token="main"><AudioEncoderConfiguration/></Profiles></GetProfilesResponse></s:Body></s:Envelope>`)
		default:
			http.Error(w, "unexpected ONVIF request", http.StatusBadRequest)
		}
	}))
	defer onvifServer.Close()

	mediaServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
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
	authManager := auth.NewManager(database, 10*time.Minute)
	session, err := authManager.BootstrapAdmin(context.Background(), auth.LoginRequest{Password: "a secure home password", DeviceName: "test"})
	if err != nil {
		t.Fatal(err)
	}
	repository := camera.NewRepository(database, vault)
	server := NewServer(
		authManager,
		repository,
		camera.NewONVIFClient(),
		media.New(mediaServer.URL, mediaServer.URL, filepath.Join(directory, "recordings")),
		nil,
		nil,
		nil,
		NewHub(),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	handler := server.Handler()
	host, port := splitServerAddress(t, onvifServer.URL)
	created := performJSON(t, handler, http.MethodPost, "/api/v1/cameras", session.Token, camera.CreateInput{
		Name: "Entrance", Host: host, Port: port, Username: "camera", Password: "secret", RTSPURI: "rtsp://camera.local/stream",
	})
	if created.Code != http.StatusAccepted {
		t.Fatalf("camera creation returned %d: %s", created.Code, created.Body.String())
	}
	select {
	case <-probeCompleted:
		t.Fatal("camera creation blocked until the ONVIF probe completed")
	default:
	}
	var started struct {
		Success bool            `json:"success"`
		Message string          `json:"message"`
		Data    CameraOperation `json:"data"`
	}
	if err = json.NewDecoder(created.Body).Decode(&started); err != nil || !started.Success || started.Message == "" || started.Data.ID == "" {
		t.Fatalf("invalid creation response: %+v err=%v", started, err)
	}
	immediate, listErr := repository.List(context.Background())
	if listErr != nil || len(immediate) != 1 || immediate[0].SetupStatus != "pending" || started.Data.Camera == nil {
		t.Fatalf("camera was not persisted immediately: cameras=%+v operation=%+v err=%v", immediate, started.Data, listErr)
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status := performJSON(t, handler, http.MethodGet, "/api/v1/camera-operations/"+started.Data.ID, session.Token, nil)
		var response struct {
			Success bool            `json:"success"`
			Message string          `json:"message"`
			Data    CameraOperation `json:"data"`
		}
		if err = json.NewDecoder(status.Body).Decode(&response); err != nil {
			t.Fatal(err)
		}
		if response.Data.Status == "failed" {
			t.Fatalf("camera operation failed: %s", response.Data.Message)
		}
		if response.Data.Status == "completed" {
			cameras, listErr := repository.List(context.Background())
			if listErr != nil || len(cameras) != 1 || response.Data.Camera == nil {
				t.Fatalf("camera was not persisted: cameras=%+v operation=%+v err=%v", cameras, response.Data, listErr)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("camera operation did not complete")
}

func TestCameraCreationPersistsProbeFailure(t *testing.T) {
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
	authManager := auth.NewManager(database, 10*time.Minute)
	session, err := authManager.BootstrapAdmin(context.Background(), auth.LoginRequest{Password: "a secure home password", DeviceName: "test"})
	if err != nil {
		t.Fatal(err)
	}
	repository := camera.NewRepository(database, vault)
	server := NewServer(authManager, repository, camera.NewONVIFClient(), media.New("http://127.0.0.1:1", "http://127.0.0.1:1", directory), nil, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	created := performJSON(t, server.Handler(), http.MethodPost, "/api/v1/cameras", session.Token, camera.CreateInput{Name: "Offline", Host: "127.0.0.1", Port: 1, Username: "camera", Password: "secret", RTSPURI: "rtsp://127.0.0.1/offline"})
	var started struct {
		Data CameraOperation `json:"data"`
	}
	if err = json.NewDecoder(created.Body).Decode(&started); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		cameras, listErr := repository.List(context.Background())
		if listErr != nil {
			t.Fatal(listErr)
		}
		if len(cameras) == 1 && cameras[0].SetupStatus == "failed" {
			if cameras[0].SetupError == "" {
				t.Fatal("setup failure was not persisted")
			}
			if strings.Contains(cameras[0].SetupError, "secret") || strings.Contains(cameras[0].SetupError, "rtsp://") {
				t.Fatalf("setup error leaked credentials: %s", cameras[0].SetupError)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("camera %s did not persist its failure", started.Data.ID)
}

func splitServerAddress(t *testing.T, raw string) (string, int) {
	t.Helper()
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	host, portText, err := net.SplitHostPort(parsed.Host)
	if err != nil {
		t.Fatal(err)
	}
	var port int
	if _, err = fmt.Sscanf(portText, "%d", &port); err != nil {
		t.Fatal(err)
	}
	return host, port
}
