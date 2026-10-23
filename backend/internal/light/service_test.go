package light

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	appcrypto "github.com/ferforastieri/valky/backend/internal/crypto"
	"github.com/ferforastieri/valky/backend/internal/store"
)

type fakeDriver struct{ state State }

func (f *fakeDriver) Available() bool { return true }
func (f *fakeDriver) Discover(context.Context, Light, Credentials) (string, error) {
	return "192.168.1.40", nil
}
func (f *fakeDriver) Status(context.Context, Light, Credentials) (State, error) {
	f.state.Online = true
	return f.state, nil
}
func (f *fakeDriver) Apply(_ context.Context, _ Light, _ Credentials, patch StatePatch) (State, error) {
	if patch.Power != nil {
		f.state.Power = *patch.Power
	}
	if patch.Brightness != nil {
		f.state.Brightness = *patch.Brightness
	}
	f.state.Online = true
	return f.state, nil
}

// fakeScannerDriver also implements Scanner, the contract the pairing flow
// needs. The failure switch makes it reject a wrong local key.
type fakeScannerDriver struct {
	fakeDriver
	reject     bool
	candidates []Candidate
}

func (f *fakeScannerDriver) Scan(context.Context, time.Duration, bool, []string) ([]Candidate, error) {
	return f.candidates, nil
}

func (f *fakeScannerDriver) Provision(context.Context, Light, Credentials) (ProvisionResult, error) {
	if f.reject {
		return ProvisionResult{}, errors.New("device rejected the key")
	}
	return ProvisionResult{
		State:        State{Online: true, Power: true, Brightness: 80, Mode: "white"},
		Capabilities: modernProfile.capabilities(),
		Version:      3.3,
		Mapping:      modernProfile.encode(),
	}, nil
}

type fakeHub struct{ messages []any }

func (h *fakeHub) Broadcast(value any) { h.messages = append(h.messages, value) }

func newTestService(t *testing.T, driver Driver) (*Service, *store.Store, *fakeHub) {
	t.Helper()
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	vault, err := appcrypto.LoadOrCreate(filepath.Join(dir, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	hub := &fakeHub{}
	service := NewService(NewRepository(db, vault), driver, hub, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return service, db, hub
}

func TestServiceControlsALightThroughTheProviderNeutralDriver(t *testing.T) {
	driver := &fakeDriver{state: State{Brightness: 70, TemperatureKelvin: 4000, Mode: "white"}}
	service, db, hub := newTestService(t, driver)
	secret, err := service.repo.vault.EncryptString("adapter-secret")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = db.DB.Exec(`INSERT INTO lights(id,name,device_id,local_key_enc,protocol_version,last_ip,capabilities_json,dp_mapping_json,setup_status,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`, "light-1", "Sala", "legacy-id", secret, 0, "", `{"brightness":true,"color":true,"colorTemperature":true}`, `{}`, "pending", 1, now, now)
	if err != nil {
		t.Fatal(err)
	}
	on := true
	brightness := 45
	item, err := service.Control(context.Background(), "light-1", StatePatch{Power: &on, Brightness: &brightness})
	if err != nil {
		t.Fatal(err)
	}
	if !item.State.Power || item.State.Brightness != 45 {
		t.Fatalf("unexpected controlled state: %+v", item.State)
	}
	_, credentials, err := service.repo.Get(context.Background(), item.ID)
	if err != nil {
		t.Fatal(err)
	}
	if credentials.Secret != "adapter-secret" || credentials.Address != "192.168.1.40" {
		t.Fatalf("credentials were not preserved: %+v", credentials)
	}
	if len(hub.messages) == 0 {
		t.Fatal("expected realtime broadcasts")
	}
}

func TestUnavailableDriverDoesNotAttemptAProviderConnection(t *testing.T) {
	driver := NewUnavailableDriver()
	_, err := driver.Discover(context.Background(), Light{}, Credentials{})
	if !errors.Is(err, ErrNoLightingAdapter) {
		t.Fatalf("expected adapter error, got %v", err)
	}
	if service, _, _ := newTestService(t, driver); service.Available() {
		t.Fatal("the unavailable driver reported itself as available")
	}
}

func TestScanListsCandidatesFromTheAdapter(t *testing.T) {
	driver := &fakeScannerDriver{candidates: []Candidate{{Address: "192.168.1.50", DeviceID: "device-1", Version: 3.3}}}
	service, _, _ := newTestService(t, driver)
	candidates, err := service.Scan(context.Background(), DiscoveryInput{})
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 1 || candidates[0].DeviceID != "device-1" {
		t.Fatalf("unexpected candidates: %+v", candidates)
	}
	// The scanner is an optional capability: the neutral driver keeps working.
	plain, _, _ := newTestService(t, &fakeDriver{})
	if _, ok := plain.Scanner(); ok {
		t.Fatal("a driver without discovery reported a scanner")
	}
	if _, err := plain.Scan(context.Background(), DiscoveryInput{}); !errors.Is(err, ErrNoLightingAdapter) {
		t.Fatalf("scan on a driver without discovery returned %v", err)
	}
}

func strptr(value string) *string { return &value }

// A busca precisa saber onde procurar quando o broadcast fica preso na rede do
// container: as faixas saem dos endereços já cadastrados.
func TestNetworksComeFromTheRegisteredAddresses(t *testing.T) {
	networks := networksOf([]string{
		"192.168.15.101",
		"192.168.15.102",
		"10.0.0.7",
		"",
		"não-é-ip",
	})
	if len(networks) != 2 || networks[0] != "10.0.0" || networks[1] != "192.168.15" {
		t.Fatalf("faixas inesperadas: %v", networks)
	}
}
