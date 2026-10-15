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

// lightAdapter answers without a device on the network so the registration flow
// can be exercised end to end. acceptKeys models a lamp that stored the key it
// was configured with; a false value models a lamp that answers but refuses the
// key (wrong key, or one only the vendor cloud knows).
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

func TestRegisterLightAdoptsAnAlreadyConfiguredLamp(t *testing.T) {
	adapter := &lightAdapter{acceptKeys: true}
	fixture := newLightFixture(t, adapter)
	body := `{"name":"Pera da sala","address":"192.168.1.60","deviceId":"device-1","localKey":"0123456789abcdef"}`
	res := fixture.do(t, http.MethodPost, "/lights", body)
	if res.Code != http.StatusCreated {
		t.Fatalf("cadastro retornou %d: %s", res.Code, res.Body.String())
	}
	created := decodeEnvelope[light.Light](t, res)
	if created.Name != "Pera da sala" || created.SetupStatus != "ready" || created.DeviceID != "device-1" {
		t.Fatalf("lâmpada cadastrada inesperada: %+v", created)
	}
	if !created.Capabilities.Color || created.ProtocolVersion != 3.3 {
		t.Fatalf("capacidades não detectadas: %+v", created)
	}
	// A chave informada é a que fica guardada, endereço incluído.
	if adapter.lastKey != "0123456789abcdef" {
		t.Fatalf("a chave validada foi %q", adapter.lastKey)
	}
	_, credentials, err := fixture.repo.Get(context.Background(), created.ID)
	if err != nil {
		t.Fatal(err)
	}
	if credentials.Secret != "0123456789abcdef" || credentials.Address != "192.168.1.60" {
		t.Fatalf("credenciais não guardadas: %+v", credentials)
	}
	// A chave local nunca aparece nas respostas.
	list := fixture.do(t, http.MethodGet, "/lights", "")
	if strings.Contains(list.Body.String(), "0123456789abcdef") {
		t.Fatal("a chave local apareceu na listagem")
	}
	// A lâmpada cadastrada pode ser controlada.
	controlled := fixture.do(t, http.MethodPut, "/lights/"+created.ID+"/state", `{"power":true}`)
	if controlled.Code != http.StatusOK {
		t.Fatalf("controle retornou %d: %s", controlled.Code, controlled.Body.String())
	}
	if strings.Contains(controlled.Body.String(), "0123456789abcdef") {
		t.Fatal("a chave local apareceu na resposta de controle")
	}
}

func TestRegisterLightValidatesTheInputAndTheDevice(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{acceptKeys: false})
	// A chave precisa ter 16 caracteres.
	short := fixture.do(t, http.MethodPost, "/lights", `{"address":"192.168.1.60","deviceId":"device-1","localKey":"curta"}`)
	if short.Code != http.StatusBadRequest {
		t.Fatalf("chave curta retornou %d: %s", short.Code, short.Body.String())
	}
	// Sem endereço não dá para chegar na lâmpada.
	missing := fixture.do(t, http.MethodPost, "/lights", `{"deviceId":"device-1","localKey":"0123456789abcdef"}`)
	if missing.Code != http.StatusBadRequest {
		t.Fatalf("sem endereço retornou %d: %s", missing.Code, missing.Body.String())
	}
	// A lâmpada que recusa a chave vira um erro claro do servidor.
	refused := fixture.do(t, http.MethodPost, "/lights", `{"address":"192.168.1.60","deviceId":"device-1","localKey":"0123456789abcdef"}`)
	if refused.Code != http.StatusBadGateway {
		t.Fatalf("chave recusada retornou %d: %s", refused.Code, refused.Body.String())
	}
}

func TestRegisterLightRefusesTheSameDeviceTwice(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{acceptKeys: true})
	body := `{"address":"192.168.1.60","deviceId":"device-1","localKey":"0123456789abcdef"}`
	if first := fixture.do(t, http.MethodPost, "/lights", body); first.Code != http.StatusCreated {
		t.Fatalf("primeiro cadastro retornou %d: %s", first.Code, first.Body.String())
	}
	second := fixture.do(t, http.MethodPost, "/lights", body)
	if second.Code != http.StatusBadRequest {
		t.Fatalf("duplicado retornou %d: %s", second.Code, second.Body.String())
	}
}
