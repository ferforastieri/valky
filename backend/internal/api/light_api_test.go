package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/auth"
	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/light"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

// lightAdapter answers without a device on the network so the pairing flow can
// be exercised end to end. acceptKeys models a lamp that stored the key it was
// configured with; a false value models a firmware that only accepts the key the
// vendor cloud issued.
type lightAdapter struct {
	acceptKeys bool
	candidates []light.Candidate
	lastKey    string
}

func (a *lightAdapter) Available() bool { return true }

func (a *lightAdapter) Discover(context.Context, light.Light, light.Credentials) (string, error) {
	return "192.168.1.60", nil
}

func (a *lightAdapter) Status(context.Context, light.Light, light.Credentials) (light.State, error) {
	return light.State{Online: true, Power: true, Brightness: 60, TemperatureKelvin: 4000}, nil
}

func (a *lightAdapter) Apply(_ context.Context, _ light.Light, _ light.Credentials, patch light.StatePatch) (light.State, error) {
	state := light.State{Online: true, Brightness: 60}
	if patch.Power != nil {
		state.Power = *patch.Power
	}
	return state, nil
}

func (a *lightAdapter) Scan(context.Context, time.Duration, bool) ([]light.Candidate, error) {
	return a.candidates, nil
}

func (a *lightAdapter) Provision(_ context.Context, _ light.Light, cred light.Credentials) (light.ProvisionResult, error) {
	if !a.acceptKeys {
		return light.ProvisionResult{}, context.DeadlineExceeded
	}
	a.lastKey = cred.Secret
	return light.ProvisionResult{
		State:        light.State{Online: true, Power: true, Brightness: 60},
		Capabilities: light.Capabilities{Brightness: true, Color: true, ColorTemperature: true},
		Version:      3.3,
		Mapping:      `{"name":"modern","switch":"20","brightness":"22","brightnessMin":10,"brightnessMax":1000}`,
	}, nil
}

type lightFixture struct {
	server  *Server
	adapter *lightAdapter
	repo    *light.Repository
	token   string
}

func newLightFixture(t *testing.T, adapter *lightAdapter) lightFixture {
	t.Helper()
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "api.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	vault, err := appcrypto.LoadOrCreate(filepath.Join(dir, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	manager := auth.NewManager(db, time.Minute)
	repository := light.NewRepository(db, vault)
	service := light.NewService(repository, adapter, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	server := NewServer(manager, nil, nil, nil, nil, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	server.SetLights(service)
	session, err := manager.CreatePairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	paired, err := manager.Pair(context.Background(), auth.PairRequest{
		Username: "admin", Password: "admin password 123", Code: session.Code, DeviceName: "Admin",
	})
	if err != nil {
		t.Fatal(err)
	}
	// Managing devices is restricted to administrators.
	if _, err := db.DB.Exec(`UPDATE users SET is_admin=1 WHERE id=(SELECT user_id FROM devices WHERE id=?)`, paired.DeviceID); err != nil {
		t.Fatal(err)
	}
	return lightFixture{server: server, adapter: adapter, repo: repository, token: paired.Token}
}

func (f lightFixture) do(t *testing.T, method, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, "/api/v1"+path, strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+f.token)
	req.Header.Set("Content-Type", "application/json")
	res := httptest.NewRecorder()
	f.server.Handler().ServeHTTP(res, req)
	return res
}

func decodeEnvelope[T any](t *testing.T, res *httptest.ResponseRecorder) T {
	t.Helper()
	var envelope struct {
		Success bool   `json:"success"`
		Data    T      `json:"data"`
		Error   string `json:"error"`
	}
	if err := json.Unmarshal(res.Body.Bytes(), &envelope); err != nil {
		t.Fatalf("resposta inválida (%d): %s", res.Code, res.Body.String())
	}
	return envelope.Data
}

func TestLightDiscoveryListsCandidatesForAdministrators(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{candidates: []light.Candidate{{Address: "192.168.1.60", DeviceID: "device-1", Version: 3.3}}})
	res := fixture.do(t, http.MethodPost, "/lights/discovery", `{"duration":1}`)
	if res.Code != http.StatusOK {
		t.Fatalf("busca retornou %d: %s", res.Code, res.Body.String())
	}
	if !strings.Contains(res.Body.String(), "192.168.1.60") {
		t.Fatalf("candidato ausente: %s", res.Body.String())
	}
}

func TestLightPairingRegistersTheLampWithTheGeneratedKey(t *testing.T) {
	adapter := &lightAdapter{acceptKeys: true, candidates: []light.Candidate{{Address: "192.168.1.60", DeviceID: "device-1", Version: 3.3}}}
	fixture := newLightFixture(t, adapter)
	session := decodeEnvelope[light.PairingSession](t, fixture.do(t, http.MethodPost, "/lights/pairing-sessions", ""))
	if session.Token == "" || session.Secret == "" || session.Region == "" {
		t.Fatalf("sessão sem material de pareamento: %+v", session)
	}
	if len(session.Token) != 16 {
		t.Fatalf("token com %d caracteres", len(session.Token))
	}
	// O aplicativo envia os dados pelo ar e acompanha o estado.
	deadline := time.Now().Add(5 * time.Second)
	var status light.PairingSession
	for time.Now().Before(deadline) {
		status = decodeEnvelope[light.PairingSession](t, fixture.do(t, http.MethodGet, "/lights/pairing-sessions/"+session.ID, ""))
		if status.Status == light.PairingPaired {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if status.Status != light.PairingPaired {
		t.Fatalf("pareamento ficou em %q: %s", status.Status, status.Message)
	}
	if status.Light == nil || status.Light.Name == "" {
		t.Fatalf("pareamento não devolveu a lâmpada: %+v", status)
	}
	if status.Light.SetupStatus != "ready" || !status.Light.Capabilities.Color {
		t.Fatalf("lâmpada pareada sem capacidades: %+v", status.Light)
	}
	// A chave guardada é o token gerado para este pareamento.
	_, credentials, err := fixture.repo.Get(context.Background(), status.Light.ID)
	if err != nil {
		t.Fatal(err)
	}
	if credentials.Secret != session.Token {
		t.Fatalf("chave guardada = %q, esperado o token do pareamento", credentials.Secret)
	}
	// A listagem mostra a lâmpada e nunca a chave.
	list := fixture.do(t, http.MethodGet, "/lights", "")
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), status.Light.Name) {
		t.Fatalf("listagem retornou %d: %s", list.Code, list.Body.String())
	}
	if strings.Contains(list.Body.String(), session.Token) {
		t.Fatal("a chave local apareceu na listagem")
	}
	// A lâmpada pareceada pode ser renomeada e controlada.
	renamed := decodeEnvelope[light.Light](t, fixture.do(t, http.MethodPut, "/lights/"+status.Light.ID, `{"name":"Pera da sala","room":"Sala"}`))
	if renamed.Name != "Pera da sala" || renamed.Room != "Sala" {
		t.Fatalf("edição não aplicada: %+v", renamed)
	}
	controlled := fixture.do(t, http.MethodPut, "/lights/"+status.Light.ID+"/state", `{"power":true}`)
	if controlled.Code != http.StatusOK {
		t.Fatalf("controle retornou %d: %s", controlled.Code, controlled.Body.String())
	}
	if strings.Contains(controlled.Body.String(), session.Token) {
		t.Fatal("a chave local apareceu na resposta de controle")
	}
}

func TestLightPairingKeepsASingleWindowAndCanBeCancelled(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{candidates: []light.Candidate{{Address: "192.168.1.60", DeviceID: "device-1", Version: 3.3}}})
	first := decodeEnvelope[light.PairingSession](t, fixture.do(t, http.MethodPost, "/lights/pairing-sessions", ""))
	second := fixture.do(t, http.MethodPost, "/lights/pairing-sessions", "")
	if second.Code != http.StatusOK {
		t.Fatalf("segunda janela retornou %d: %s", second.Code, second.Body.String())
	}
	if reused := decodeEnvelope[light.PairingSession](t, second); reused.ID != first.ID {
		t.Fatalf("abriu uma segunda janela: %s != %s", reused.ID, first.ID)
	}
	cancelled := fixture.do(t, http.MethodDelete, "/lights/pairing-sessions/"+first.ID, "")
	if cancelled.Code != http.StatusOK {
		t.Fatalf("cancelamento retornou %d: %s", cancelled.Code, cancelled.Body.String())
	}
	if missing := fixture.do(t, http.MethodGet, "/lights/pairing-sessions/"+first.ID, ""); missing.Code != http.StatusNotFound {
		t.Fatalf("status após cancelar retornou %d", missing.Code)
	}
	// Uma nova janela pode ser aberta depois de cancelar.
	third := fixture.do(t, http.MethodPost, "/lights/pairing-sessions", "")
	if third.Code != http.StatusCreated {
		t.Fatalf("nova janela retornou %d: %s", third.Code, third.Body.String())
	}
}

func TestLightPairingStatusIsUnknownForOtherIdentifiers(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{})
	if res := fixture.do(t, http.MethodGet, "/lights/pairing-sessions/desconhecido", ""); res.Code != http.StatusNotFound {
		t.Fatalf("identificador desconhecido retornou %d", res.Code)
	}
	if res := fixture.do(t, http.MethodDelete, "/lights/pairing-sessions/desconhecido", ""); res.Code != http.StatusNotFound {
		t.Fatalf("cancelamento desconhecido retornou %d", res.Code)
	}
}
