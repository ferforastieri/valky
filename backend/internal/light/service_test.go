package light

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
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

type fakeHub struct{ messages []any }

func (h *fakeHub) Broadcast(value any) { h.messages = append(h.messages, value) }

func TestServiceControlsALightThroughTheProviderNeutralDriver(t *testing.T) {
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	vault, err := appcrypto.LoadOrCreate(filepath.Join(dir, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	driver := &fakeDriver{state: State{Brightness: 70, TemperatureKelvin: 4000, Mode: "white"}}
	hub := &fakeHub{}
	service := NewService(NewRepository(db, vault), driver, hub, slog.New(slog.NewTextHandler(io.Discard, nil)))
	secret, err := vault.EncryptString("adapter-secret")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = db.DB.Exec(`INSERT INTO lights(id,name,room,device_id,local_key_enc,protocol_version,last_ip,capabilities_json,dp_mapping_json,setup_status,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`, "light-1", "Sala", "Sala", "legacy-id", secret, 0, "", `{"brightness":true,"color":true,"colorTemperature":true}`, `{}`, "pending", 1, now, now)
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
}
