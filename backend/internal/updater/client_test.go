package updater

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestClientTalksToUnixSocket(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "updater.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var calls atomic.Int32
	mux := http.NewServeMux()
	mux.HandleFunc("POST /update", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		if calls.Add(1) > 1 {
			w.WriteHeader(http.StatusConflict)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "running"})
			return
		}
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(Status{State: "running"})
	})
	mux.HandleFunc("GET /status", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(Status{State: "succeeded", Output: "ok"})
	})
	server := &http.Server{Handler: mux}
	go func() { _ = server.Serve(listener) }()
	defer server.Close()

	client := New(socket, "secret")
	if !client.Enabled() {
		t.Fatal("updater deveria estar habilitado")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	status, err := client.Start(ctx)
	if err != nil || status.State != "running" {
		t.Fatalf("start = %+v, %v", status, err)
	}
	if _, err := client.Start(ctx); !errors.Is(err, ErrBusy) {
		t.Fatalf("segundo start = %v", err)
	}
	status, err = client.Status(ctx)
	if err != nil || status.State != "succeeded" || status.Output != "ok" {
		t.Fatalf("status = %+v, %v", status, err)
	}
}

func TestClientDisabledWithoutToken(t *testing.T) {
	if New("/tmp/does-not-exist", "").Enabled() {
		t.Fatal("updater sem token não deveria estar habilitado")
	}
}
