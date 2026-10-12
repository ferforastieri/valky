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

// lightAdapter is a local adapter that answers without a device on the network,
// so the registration flow can be exercised end to end.
type lightAdapter struct {
	reject     bool
	candidates []light.Candidate
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

func (a *lightAdapter) Provision(context.Context, light.Light, light.Credentials) (light.ProvisionResult, error) {
	if a.reject {
		return light.ProvisionResult{}, context.DeadlineExceeded
	}
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
	service := light.NewService(light.NewRepository(db, vault), adapter, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))
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
	// Cadastro de dispositivos é restrito a administradores.
	if _, err := db.DB.Exec(`UPDATE users SET is_admin=1 WHERE id=(SELECT user_id FROM devices WHERE id=?)`, paired.DeviceID); err != nil {
		t.Fatal(err)
	}
	return lightFixture{server: server, adapter: adapter, token: paired.Token}
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

func decodeLight(t *testing.T, res *httptest.ResponseRecorder) light.Light {
	t.Helper()
	var envelope struct {
		Success bool        `json:"success"`
		Data    light.Light `json:"data"`
		Error   string      `json:"error"`
	}
	if err := json.Unmarshal(res.Body.Bytes(), &envelope); err != nil {
		t.Fatalf("resposta inválida (%d): %s", res.Code, res.Body.String())
	}
	return envelope.Data
}

func TestRegisteringALightDetectsCapabilitiesAndNeverReturnsTheKey(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{})
	created := fixture.do(t, http.MethodPost, "/lights", `{"name":"Pera da sala","room":"Sala","deviceId":"device-1","localKey":"0123456789abcdef"}`)
	if created.Code != http.StatusCreated {
		t.Fatalf("cadastro retornou %d: %s", created.Code, created.Body.String())
	}
	if strings.Contains(created.Body.String(), "0123456789abcdef") {
		t.Fatal("a chave local apareceu na resposta da API")
	}
	id := decodeLight(t, created).ID
	if id == "" {
		t.Fatal("a lâmpada criada não trouxe identificador")
	}
	// Provisionamento é assíncrono: espera ficar pronta.
	deadline := time.Now().Add(3 * time.Second)
	var item light.Light
	for time.Now().Before(deadline) {
		item = decodeLight(t, fixture.do(t, http.MethodGet, "/lights/"+id, ""))
		if item.SetupStatus == "ready" {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if item.SetupStatus != "ready" {
		t.Fatalf("a lâmpada ficou em %q (%s)", item.SetupStatus, item.SetupError)
	}
	if !item.Capabilities.Color || !item.Capabilities.Brightness {
		t.Fatalf("capacidades não detectadas: %+v", item.Capabilities)
	}
	if item.ProtocolVersion != 3.3 {
		t.Fatalf("versão detectada = %.1f", item.ProtocolVersion)
	}
	list := fixture.do(t, http.MethodGet, "/lights", "")
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), "Pera da sala") {
		t.Fatalf("listagem retornou %d: %s", list.Code, list.Body.String())
	}
}

func TestLightRegistrationValidatesInput(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{})
	cases := map[string]string{
		"sem chave":    `{"name":"Sala","deviceId":"device-1","localKey":"curta"}`,
		"sem nome":     `{"deviceId":"device-1","localKey":"0123456789abcdef"}`,
		"sem aparelho": `{"name":"Sala","localKey":"0123456789abcdef"}`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			res := fixture.do(t, http.MethodPost, "/lights", body)
			if res.Code != http.StatusBadRequest {
				t.Fatalf("retornou %d: %s", res.Code, res.Body.String())
			}
		})
	}
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

func TestLightUpdateReplacesCredentialsAndControlReportsTheDevice(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{})
	created := decodeLight(t, fixture.do(t, http.MethodPost, "/lights", `{"name":"Sala","deviceId":"device-1","localKey":"0123456789abcdef"}`))
	updated := fixture.do(t, http.MethodPut, "/lights/"+created.ID, `{"name":"Luminária","room":"Escritório","localKey":"fedcba9876543210"}`)
	if updated.Code != http.StatusOK {
		t.Fatalf("edição retornou %d: %s", updated.Code, updated.Body.String())
	}
	item := decodeLight(t, updated)
	if item.Name != "Luminária" || item.Room != "Escritório" {
		t.Fatalf("edição não aplicada: %+v", item)
	}
	if strings.Contains(updated.Body.String(), "fedcba9876543210") {
		t.Fatal("a chave nova apareceu na resposta")
	}
	controlled := fixture.do(t, http.MethodPut, "/lights/"+created.ID+"/state", `{"power":true}`)
	if controlled.Code != http.StatusOK {
		t.Fatalf("controle retornou %d: %s", controlled.Code, controlled.Body.String())
	}
	if strings.Contains(controlled.Body.String(), "0123456789abcdef") {
		t.Fatal("a chave local apareceu na resposta de controle")
	}
}

func TestLightRegistrationSurfacesAFailedPairing(t *testing.T) {
	fixture := newLightFixture(t, &lightAdapter{reject: true})
	created := decodeLight(t, fixture.do(t, http.MethodPost, "/lights", `{"name":"Sala","deviceId":"device-1","localKey":"0123456789abcdef"}`))
	deadline := time.Now().Add(3 * time.Second)
	var item light.Light
	for time.Now().Before(deadline) {
		item = decodeLight(t, fixture.do(t, http.MethodGet, "/lights/"+created.ID, ""))
		if item.SetupStatus == "failed" {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if item.SetupStatus != "failed" || item.SetupError == "" {
		t.Fatalf("falha de pareamento não foi reportada: %+v", item)
	}
}
