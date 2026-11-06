package api

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"github.com/ferforastieri/valky/backend/internal/auth"
	"github.com/ferforastieri/valky/backend/internal/camera"
	appcrypto "github.com/ferforastieri/valky/backend/internal/crypto"
	"github.com/ferforastieri/valky/backend/internal/store"
)

func newDiscoveryServer(t *testing.T) (*Server, *camera.Repository, string) {
	t.Helper()
	directory := t.TempDir()
	database, err := store.Open(filepath.Join(directory, "valky.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })
	vault, err := appcrypto.LoadOrCreate(filepath.Join(directory, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	manager := auth.NewManager(database, 10*time.Minute)
	session, err := manager.BootstrapAdmin(context.Background(), auth.LoginRequest{Username: "admin", Password: "a secure home password", DeviceName: "test"})
	if err != nil {
		t.Fatal(err)
	}
	repository := camera.NewRepository(database, vault)
	server := NewServer(manager, repository, camera.NewONVIFClient(), nil, nil, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	// A varredura real toca 254 endereços; no teste ela é trocada para a ronda
	// ser determinística e rápida.
	server.cameraScan = func(context.Context, string, int, time.Duration) []string { return nil }
	return server, repository, session.Token
}

func TestDiscoverCameraReportsMissingCamera(t *testing.T) {
	server, repository, token := newDiscoveryServer(t)
	cam, err := repository.CreatePending(context.Background(), camera.CreateInput{Name: "Entrada", Host: "192.168.15.50", Username: "camera", Password: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	if err = repository.SetIdentity(context.Background(), cam.ID, "98:03:8E:B5:31:F7", "SERIAL-1"); err != nil {
		t.Fatal(err)
	}
	response := performJSON(t, server.Handler(), http.MethodPost, "/api/v1/cameras/"+cam.ID+"/discovery", token, nil)
	if response.Code != http.StatusNotFound {
		t.Fatalf("câmera ausente deveria responder 404, veio %d: %s", response.Code, response.Body.String())
	}
}

func TestDiscoverCameraRejectsUnknownCamera(t *testing.T) {
	server, _, token := newDiscoveryServer(t)
	response := performJSON(t, server.Handler(), http.MethodPost, "/api/v1/cameras/unknown/discovery", token, nil)
	if response.Code != http.StatusNotFound {
		t.Fatalf("câmera inexistente deveria responder 404, veio %d", response.Code)
	}
}
