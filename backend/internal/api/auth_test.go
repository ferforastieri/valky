package api

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestFirstUserCreatesAdministratorAndInvites(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "valkyris.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	manager := auth.NewManager(db, 10*time.Minute)
	server := NewServer(manager, nil, nil, nil, nil, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	handler := server.Handler()

	status := performJSON(t, handler, http.MethodGet, "/api/v1/auth/status", "", nil)
	if status.Code != http.StatusOK || !bytes.Contains(status.Body.Bytes(), []byte(`"initialized":false`)) {
		t.Fatalf("unexpected initial status: %d %s", status.Code, status.Body.String())
	}

	credentials := auth.LoginRequest{Password: "a secure home password", DeviceName: "Pixel", Locale: "pt-BR"}
	created := performJSON(t, handler, http.MethodPost, "/api/v1/admin/bootstrap", "", credentials)
	if created.Code != http.StatusCreated {
		t.Fatalf("bootstrap returned %d: %s", created.Code, created.Body.String())
	}
	var response struct {
		Success bool              `json:"success"`
		Message string            `json:"message"`
		Data    auth.PairResponse `json:"data"`
	}
	if err = json.NewDecoder(created.Body).Decode(&response); err != nil || !response.Success || !response.Data.Admin || response.Data.Token == "" {
		t.Fatalf("unexpected administrator response: %+v err=%v", response, err)
	}
	if created.Header().Get(messageHeader) == "" || created.Header().Get(successHeader) != "true" {
		t.Fatalf("missing outcome headers: %v", created.Header())
	}
	session := response.Data
	if !session.Admin || session.Token == "" {
		t.Fatalf("unexpected administrator session: %+v err=%v", session, err)
	}

	duplicate := performJSON(t, handler, http.MethodPost, "/api/v1/admin/bootstrap", "", credentials)
	if duplicate.Code != http.StatusConflict {
		t.Fatalf("second bootstrap returned %d", duplicate.Code)
	}
	if !bytes.Contains(duplicate.Body.Bytes(), []byte(`"success":false`)) || !bytes.Contains(duplicate.Body.Bytes(), []byte(`"message"`)) {
		t.Fatalf("error response has no complete message envelope: %s", duplicate.Body.String())
	}
	invite := performJSON(t, handler, http.MethodPost, "/api/v1/pairing-sessions", session.Token, nil)
	if invite.Code != http.StatusCreated || !bytes.Contains(invite.Body.Bytes(), []byte(`"code"`)) {
		t.Fatalf("administrator invite returned %d: %s", invite.Code, invite.Body.String())
	}
}

func performJSON(t *testing.T, handler http.Handler, method, target, token string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		payload = bytes.NewReader(encoded)
	}
	request := httptest.NewRequest(method, target, payload)
	request.Header.Set("Content-Type", "application/json")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}
