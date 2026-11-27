package tuyaimport

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/ferforastieri/valky/backend/internal/light"
	"github.com/ferforastieri/valky/backend/internal/light/tuya"
)

type fakeCloud struct {
	authorized bool
	login      *tuya.CloudLogin
	devices    []tuya.CloudDevice
	listErr    error
	polls      int
}

func (f *fakeCloud) StartQR(context.Context, string) (string, error) { return "qr-token", nil }

func (f *fakeCloud) PollQR(context.Context, string, string) (*tuya.CloudLogin, bool, error) {
	f.polls++
	if !f.authorized {
		return nil, false, nil
	}
	return f.login, true, nil
}

func (f *fakeCloud) ListDevices(context.Context, *tuya.CloudLogin) ([]tuya.CloudDevice, error) {
	return f.devices, f.listErr
}

type fakeRegistrar struct {
	calls      []light.RegisterInput
	err        error
	expected   map[string]string
	candidates []light.Candidate
}

func (f *fakeRegistrar) Register(_ context.Context, in light.RegisterInput) (light.Light, error) {
	f.calls = append(f.calls, in)
	if f.err != nil {
		return light.Light{}, f.err
	}
	if f.expected != nil && f.expected[in.DeviceID] != in.Address {
		return light.Light{}, errors.New("endereço não confere")
	}
	return light.Light{ID: "light-" + in.DeviceID, Name: in.Name}, nil
}

func (f *fakeRegistrar) Scan(context.Context, light.DiscoveryInput) ([]light.Candidate, error) {
	return f.candidates, nil
}

func TestImportFlow(t *testing.T) {
	cloud := &fakeCloud{
		login: &tuya.CloudLogin{AccessToken: "token"},
		devices: []tuya.CloudDevice{
			{ID: "dev-online", Name: "Sala", IP: "192.168.15.107", Online: true, LocalKey: "0123456789abcdef", ProductID: "prod-1"},
			{ID: "dev-offline", Name: "Quarto", IP: "192.168.15.105", Online: false, LocalKey: "fedcba9876543210"},
			{ID: "dev-zigbee", Name: "Corredor", IP: "192.168.15.106", Online: true, LocalKey: "", Sub: true},
		},
	}
	registrar := &fakeRegistrar{expected: map[string]string{"dev-online": "192.168.15.107"}}
	manager := NewManagerWithCloud(registrar, cloud)
	ctx := context.Background()

	start, err := manager.Start(ctx, "123456", nil)
	if err != nil {
		t.Fatal(err)
	}
	if start.QRContent != tuya.QRContentPrefix+"qr-token" {
		t.Fatalf("QR inesperado: %q", start.QRContent)
	}

	status, err := manager.Poll(ctx, start.SessionID)
	if err != nil || status.Status != "pending" {
		t.Fatalf("poll pendente = %#v, %v", status, err)
	}

	cloud.authorized = true
	status, err = manager.Poll(ctx, start.SessionID)
	if err != nil || status.Status != "authorized" || len(status.Devices) != 3 {
		t.Fatalf("poll autorizado = %#v, %v", status, err)
	}
	if status.Devices[0].HasLocalKey != true || status.Devices[2].HasLocalKey != false {
		t.Fatalf("hasLocalKey inesperado: %#v", status.Devices)
	}

	result, err := manager.Apply(ctx, start.SessionID, []string{"dev-online", "dev-offline", "dev-zigbee"})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Imported) != 1 || len(result.Failed) != 2 {
		t.Fatalf("resultado inesperado: %#v", result)
	}
	var onlineCall *light.RegisterInput
	for index := range registrar.calls {
		if registrar.calls[index].DeviceID == "dev-online" {
			onlineCall = &registrar.calls[index]
		}
	}
	if len(registrar.calls) != 2 || onlineCall == nil || onlineCall.Address != "192.168.15.107" || onlineCall.LocalKey != "0123456789abcdef" {
		t.Fatalf("cadastro inesperado: %#v", registrar.calls)
	}
	if result.Failed[0].Error == "" || result.Failed[1].Error == "" {
		t.Fatalf("falhas sem explicação: %#v", result.Failed)
	}
}

func TestApplyDiscoversMissingIP(t *testing.T) {
	cloud := &fakeCloud{
		login: &tuya.CloudLogin{AccessToken: "token"},
		devices: []tuya.CloudDevice{
			{ID: "dev-1", Name: "Sala", IP: "", Online: true, LocalKey: "0123456789abcdef"},
		},
	}
	registrar := &fakeRegistrar{
		expected:   map[string]string{"dev-1": "192.168.15.107"},
		candidates: []light.Candidate{{Address: "192.168.15.105"}, {Address: "192.168.15.107"}},
	}
	manager := NewManagerWithCloud(registrar, cloud)
	ctx := context.Background()
	start, err := manager.Start(ctx, "123456", []string{"192.168.15"})
	if err != nil {
		t.Fatal(err)
	}
	cloud.authorized = true
	if _, err := manager.Poll(ctx, start.SessionID); err != nil {
		t.Fatal(err)
	}
	result, err := manager.Apply(ctx, start.SessionID, []string{"dev-1"})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Imported) != 1 || len(result.Failed) != 0 {
		t.Fatalf("resultado = %#v", result)
	}
	if len(registrar.calls) != 2 || registrar.calls[0].Address != "192.168.15.105" || registrar.calls[1].Address != "192.168.15.107" {
		t.Fatalf("tentativas = %#v", registrar.calls)
	}
}

func TestApplyBeforeAuthorization(t *testing.T) {
	manager := NewManagerWithCloud(&fakeRegistrar{}, &fakeCloud{})
	start, err := manager.Start(context.Background(), "123456", nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Apply(context.Background(), start.SessionID, []string{"dev"}); !errors.Is(err, ErrNotAuthorized) {
		t.Fatalf("erro = %v", err)
	}
}

func TestCancelAndExpiry(t *testing.T) {
	now := time.Date(2026, 11, 27, 12, 0, 0, 0, time.UTC)
	manager := NewManagerWithCloud(&fakeRegistrar{}, &fakeCloud{})
	manager.now = func() time.Time { return now }
	start, err := manager.Start(context.Background(), "123456", nil)
	if err != nil {
		t.Fatal(err)
	}
	manager.Cancel(start.SessionID)
	if _, err := manager.Poll(context.Background(), start.SessionID); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("erro após cancelar = %v", err)
	}

	start, err = manager.Start(context.Background(), "123456", nil)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(SessionTTL + time.Second)
	status, err := manager.Poll(context.Background(), start.SessionID)
	if err != nil || status.Status != "expired" {
		t.Fatalf("status expirado = %#v, %v", status, err)
	}
}

func TestListErrorBecomesStatusError(t *testing.T) {
	cloud := &fakeCloud{authorized: true, login: &tuya.CloudLogin{AccessToken: "token"}, listErr: errors.New("sem rede")}
	manager := NewManagerWithCloud(&fakeRegistrar{}, cloud)
	start, err := manager.Start(context.Background(), "123456", nil)
	if err != nil {
		t.Fatal(err)
	}
	status, err := manager.Poll(context.Background(), start.SessionID)
	if err != nil || status.Status != "error" || !strings.Contains(status.Error, "sem rede") {
		t.Fatalf("status = %#v, %v", status, err)
	}
}
