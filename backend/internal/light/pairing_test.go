package light

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

// ScriptedAdapter answers the pairing flow without a device: it lists whatever
// candidates the test asks for and accepts the key after the configured number
// of refusals, which models a lamp that stored something other than the token.
type ScriptedAdapter struct {
	candidates  []Candidate
	acceptAny   bool
	acceptAfter int
	mu          sync.Mutex
	attempts    []string
}

func (a *ScriptedAdapter) attempted() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]string{}, a.attempts...)
}

func (a *ScriptedAdapter) Available() bool { return true }

func (a *ScriptedAdapter) Discover(context.Context, Light, Credentials) (string, error) {
	if len(a.candidates) > 0 {
		return a.candidates[0].Address, nil
	}
	return "", context.DeadlineExceeded
}

func (a *ScriptedAdapter) Status(context.Context, Light, Credentials) (State, error) {
	return State{Online: true}, nil
}

func (a *ScriptedAdapter) Apply(context.Context, Light, Credentials, StatePatch) (State, error) {
	return State{Online: true}, nil
}

func (a *ScriptedAdapter) Scan(context.Context, time.Duration, bool) ([]Candidate, error) {
	return a.candidates, nil
}

func (a *ScriptedAdapter) Provision(_ context.Context, _ Light, cred Credentials) (ProvisionResult, error) {
	a.mu.Lock()
	a.attempts = append(a.attempts, cred.Secret)
	attempts := len(a.attempts)
	a.mu.Unlock()
	if a.acceptAny || (attempts > a.acceptAfter && a.acceptAfter > 0) {
		return ProvisionResult{
			State:        State{Online: true, Power: true},
			Capabilities: modernProfile.capabilities(),
			Version:      3.3,
			Mapping:      modernProfile.encode(),
		}, nil
	}
	return ProvisionResult{}, context.DeadlineExceeded
}

func newPairingService(t *testing.T, adapter interface {
	Driver
	Scanner
}) *Service {
	t.Helper()
	service, _, _ := newTestService(t, adapter)
	service.pairingWindow = 400 * time.Millisecond
	return service
}

func waitForStatus(t *testing.T, service *Service, id, status string) PairingSession {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var session PairingSession
	for time.Now().Before(deadline) {
		var err error
		session, err = service.PairingStatus(id)
		if err != nil {
			t.Fatal(err)
		}
		if session.Status == status {
			return session
		}
		time.Sleep(20 * time.Millisecond)
	}
	return session
}

func TestPairingRegistersALampThatAcceptedTheGeneratedToken(t *testing.T) {
	adapter := &ScriptedAdapter{candidates: []Candidate{{Address: "192.168.1.60", DeviceID: "device-1", Version: 3.3}}, acceptAny: true}
	service := newPairingService(t, adapter)
	session, err := service.StartPairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(session.Token) != 16 || len(session.Secret) != 4 || session.Region == "" {
		t.Fatalf("material de pareamento inválido: %+v", session)
	}
	final := waitForStatus(t, service, session.ID, PairingPaired)
	if final.Light == nil {
		t.Fatalf("pareamento sem lâmpada: %+v", final)
	}
	if final.Light.SetupStatus != "ready" || !final.Light.Capabilities.Brightness {
		t.Fatalf("lâmpada registrada sem capacidades: %+v", final.Light)
	}
	// A primeira tentativa é sempre o token gerado para esta janela.
	attempts := adapter.attempted()
	if len(attempts) == 0 || attempts[0] != session.Token {
		t.Fatalf("primeira chave tentada = %q, esperado o token", attempts)
	}
	// A chave fica guardada e a lâmpada aparece na listagem.
	_, credentials, err := service.repo.Get(context.Background(), final.Light.ID)
	if err != nil {
		t.Fatal(err)
	}
	if credentials.Secret != session.Token || credentials.Address != "192.168.1.60" {
		t.Fatalf("credenciais guardadas = %+v", credentials)
	}
	list, err := service.List(context.Background())
	if err != nil || len(list) != 1 {
		t.Fatalf("listagem após parear = %d itens (%v)", len(list), err)
	}
}

func TestPairingWalksTheKeyCandidatesUntilOneAnswers(t *testing.T) {
	// A lâmpada aceita apenas uma chave derivada, não o token: o serviço deve
	// continuar tentando em vez de desistir na primeira recusa.
	adapter := &ScriptedAdapter{candidates: []Candidate{{Address: "192.168.1.61", DeviceID: "device-abc", Version: 3.3}}}
	service := newPairingService(t, adapter)
	session, err := service.StartPairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	// O token é a primeira tentativa; a lâmpada aceita a partir da terceira.
	adapter.acceptAfter = 2
	final := waitForStatus(t, service, session.ID, PairingPaired)
	if final.Light == nil {
		t.Fatalf("pareamento não concluiu: %+v", final)
	}
	if attempts := adapter.attempted(); len(attempts) < 3 {
		t.Fatalf("a lâmpada foi registrada sem percorrer as chaves: %v", attempts)
	}
}

func TestPairingReportsALampThatRefusesEveryKey(t *testing.T) {
	adapter := &ScriptedAdapter{candidates: []Candidate{{Address: "192.168.1.62", DeviceID: "device-2", Version: 3.3}}}
	service := newPairingService(t, adapter)
	session, err := service.StartPairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	final := waitForStatus(t, service, session.ID, PairingFailed)
	if final.Address == "" || !strings.Contains(final.Message, "não aceitou") {
		t.Fatalf("falha sem explicação útil: %+v", final)
	}
	if lights, err := service.List(context.Background()); err != nil || len(lights) != 0 {
		t.Fatalf("uma lâmpada foi registrada sem aceitar a chave: %v", lights)
	}
}

func TestPairingKeyCandidatesAreSixteenCharactersAndLeadWithTheToken(t *testing.T) {
	session := PairingSession{Token: "AbCdEfGh12345678", Secret: "pr_0", Region: "AY"}
	candidate := Candidate{Address: "192.168.1.63", DeviceID: "device-3", MAC: "aa:bb:cc:dd:ee:ff"}
	keys := pairingKeyCandidates(session, candidate)
	if keys[0] != session.Token {
		t.Fatalf("a primeira chave não é o token: %q", keys[0])
	}
	for _, key := range keys {
		if len(key) != 16 {
			t.Fatalf("chave com %d caracteres: %q", len(key), key)
		}
	}
	// O token aparece uma única vez, mesmo somando as variações.
	seen := map[string]int{}
	for _, key := range keys {
		seen[key]++
	}
	if seen[session.Token] != 1 {
		t.Fatalf("token repetido na lista: %v", seen)
	}
}

func TestPairingRefusesASecondWindow(t *testing.T) {
	adapter := &ScriptedAdapter{}
	service := newPairingService(t, adapter)
	first, err := service.StartPairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.StartPairing(context.Background())
	if err != ErrPairingInProgress {
		t.Fatalf("erro ao abrir a segunda janela: %v", err)
	}
	if second.ID != first.ID {
		t.Fatal("a segunda janela deveria reaproveitar a primeira")
	}
	if err := service.CancelPairing(first.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := service.PairingStatus(first.ID); err == nil {
		t.Fatal("a janela cancelada continua acessível")
	}
}

func TestPairingWithoutALocalAdapterIsReported(t *testing.T) {
	service, _, _ := newTestService(t, NewUnavailableDriver())
	if _, err := service.StartPairing(context.Background()); err != ErrNoLightingAdapter {
		t.Fatalf("adaptador ausente retornou %v", err)
	}
}
