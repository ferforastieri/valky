package api

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/ferforastieri/valky/backend/internal/updater"
)

func TestStartSystemUpdateWithoutUpdater(t *testing.T) {
	server := &Server{}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/system/update", nil)
	res := httptest.NewRecorder()
	server.startSystemUpdate(res, req)
	if res.Code != http.StatusServiceUnavailable {
		t.Fatalf("sem updater = %d", res.Code)
	}
	server.SetUpdater(updater.New("", ""))
	res = httptest.NewRecorder()
	server.startSystemUpdate(res, req)
	if res.Code != http.StatusServiceUnavailable {
		t.Fatalf("updater desabilitado = %d", res.Code)
	}
}

func TestStartSystemUpdateMapsUpdaterResponses(t *testing.T) {
	for _, tc := range []struct {
		name string
		code int
		want int
	}{
		{"accepted", http.StatusAccepted, http.StatusAccepted},
		{"busy", http.StatusConflict, http.StatusConflict},
		{"failure", http.StatusInternalServerError, http.StatusBadGateway},
	} {
		t.Run(tc.name, func(t *testing.T) {
			socket := filepath.Join(t.TempDir(), "updater.sock")
			listener, err := net.Listen("unix", socket)
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != "Bearer secret" {
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				w.WriteHeader(tc.code)
				if tc.code == http.StatusAccepted {
					_ = json.NewEncoder(w).Encode(updater.Status{State: "running"})
				}
			})}
			go func() { _ = server.Serve(listener) }()
			defer server.Close()

			apiServer := &Server{updater: updater.New(socket, "secret")}
			req := httptest.NewRequest(http.MethodPost, "/api/v1/system/update", nil)
			res := httptest.NewRecorder()
			apiServer.startSystemUpdate(res, req)
			if res.Code != tc.want {
				t.Fatalf("código = %d, esperado %d (%s)", res.Code, tc.want, res.Body.String())
			}
		})
	}
}
