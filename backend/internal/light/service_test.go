package light

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"testing"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

type fakeDriver struct{ state State }

func (f *fakeDriver) Discover(context.Context, Light, Credentials) (string, float64, error) {
	return "192.168.1.40", 3.5, nil
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

func TestServiceCreatesAndControlsLocalLight(t *testing.T) {
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
	item, err := service.Create(context.Background(), CreateInput{Name: "Sala", Room: "Sala", DeviceID: "device-1", LocalKey: "1234567890abcdef"})
	if err != nil {
		t.Fatal(err)
	}
	if item.ProtocolVersion != 3.5 || !item.State.Online {
		t.Fatalf("unexpected created light: %+v", item)
	}
	on := true
	brightness := 45
	item, err = service.Control(context.Background(), item.ID, StatePatch{Power: &on, Brightness: &brightness})
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
	if credentials.LocalKey != "1234567890abcdef" || credentials.LastIP != "192.168.1.40" {
		t.Fatalf("credentials were not preserved: %+v", credentials)
	}
	if len(hub.messages) == 0 {
		t.Fatal("expected realtime broadcasts")
	}
}

func TestEncodeAndDecodeModernLightDPs(t *testing.T) {
	mapping := DefaultDPMapping()
	brightness := 80
	temperature := 4600
	color := Color{Hue: 220, Saturation: 75, Value: 80}
	values, err := encodePatch(StatePatch{Brightness: &brightness, TemperatureKelvin: &temperature, Color: &color}, mapping)
	if err != nil {
		t.Fatal(err)
	}
	if values["22"] != 800 || values["21"] != "colour" {
		t.Fatalf("unexpected encoded values: %#v", values)
	}
	state := decodeState(map[string]any{"20": true, "21": "colour", "22": float64(800), "23": float64(500), "24": "00dc02ee0320"}, mapping)
	if !state.Power || state.Mode != "color" || state.Brightness != 80 || state.Color.Hue != 220 || state.Color.Saturation != 75 {
		t.Fatalf("unexpected decoded state: %+v", state)
	}
}
