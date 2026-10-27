package remote

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
	"github.com/google/uuid"
)

// movingDriver simula uma TV que só responde no endereço novo.
type movingDriver struct {
	reachable map[string]bool
	macs      map[string]string
	sent      []string
}

func (d *movingDriver) Available() bool { return true }

func (d *movingDriver) Pair(context.Context, Device) (string, error) { return "", nil }

func (d *movingDriver) Send(_ context.Context, device Device, _, _ string) error {
	d.sent = append(d.sent, device.Host)
	if !d.reachable[device.Host] {
		return errors.New("não foi possível alcançar a TV em " + device.Host)
	}
	return nil
}

func (d *movingDriver) SendText(ctx context.Context, device Device, token, text string) error {
	return d.Send(ctx, device, token, text)
}

func (d *movingDriver) DeviceMAC(_ context.Context, device Device) (string, error) {
	mac, ok := d.macs[device.Host]
	if !ok {
		return "", errors.New("endereço não é uma TV")
	}
	return mac, nil
}

func (d *movingDriver) Reachable(_ context.Context, device Device) bool {
	return d.reachable[device.Host]
}

func newTestService(t *testing.T, driver Driver) (*Service, *Repository) {
	t.Helper()
	directory := t.TempDir()
	database, err := store.Open(filepath.Join(directory, "valky.db"))
	if err != nil {
		t.Fatalf("store: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	vault, err := appcrypto.LoadOrCreate(filepath.Join(directory, "master.key"))
	if err != nil {
		t.Fatalf("vault: %v", err)
	}
	repository := NewRepository(database, vault)
	service := NewService(repository, driver, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return service, repository
}

func TestSendFollowsTVToNewAddress(t *testing.T) {
	driver := &movingDriver{
		reachable: map[string]bool{"192.168.15.108": true},
		macs:      map[string]string{"192.168.15.108": "80:8A:BD:0B:97:EC"},
	}
	service, repository := newTestService(t, driver)
	created, err := repository.Create(context.Background(), Device{
		ID: uuid.NewString(), Name: "TV", Kind: KindSmartTV,
		Host: "192.168.15.105", Port: DefaultPort, MAC: "80:8a:bd:0b:97:ec",
		SetupStatus: "ready", Enabled: true,
	}, "token")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	service.scan = func(_ context.Context, known string, _ []int, _ time.Duration) []string {
		if known != "192.168.15.105" {
			t.Errorf("varredura partiu de %q", known)
		}
		return []string{"192.168.15.108", "192.168.15.1"}
	}

	updated, err := service.Send(context.Background(), created.ID, "KEY_HOME")
	if err != nil {
		t.Fatalf("send: %v", err)
	}
	if updated.Host != "192.168.15.108" {
		t.Fatalf("host devolvido = %q", updated.Host)
	}
	if len(driver.sent) != 2 || driver.sent[0] != "192.168.15.105" || driver.sent[1] != "192.168.15.108" {
		t.Fatalf("tentativas = %v", driver.sent)
	}
	stored, _, err := repository.Get(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if stored.Host != "192.168.15.108" || stored.SetupStatus != "ready" {
		t.Fatalf("registro = %+v", stored)
	}
}

func TestSendKeepsAddressWhenMACDoesNotMatch(t *testing.T) {
	driver := &movingDriver{
		reachable: map[string]bool{},
		macs:      map[string]string{"192.168.15.108": "aa:bb:cc:dd:ee:ff"},
	}
	service, repository := newTestService(t, driver)
	created, err := repository.Create(context.Background(), Device{
		ID: uuid.NewString(), Name: "TV", Kind: KindSmartTV,
		Host: "192.168.15.105", Port: DefaultPort, MAC: "80:8A:BD:0B:97:EC",
		SetupStatus: "ready", Enabled: true,
	}, "token")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	service.scan = func(context.Context, string, []int, time.Duration) []string {
		return []string{"192.168.15.108"}
	}

	if _, err := service.Send(context.Background(), created.ID, "KEY_HOME"); err == nil {
		t.Fatal("esperava erro quando o MAC não confere")
	}
	stored, _, err := repository.Get(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if stored.Host != "192.168.15.105" {
		t.Fatalf("endereço não podia mudar: %q", stored.Host)
	}
}

func TestSendFailsFastWhileOffline(t *testing.T) {
	driver := &movingDriver{
		reachable: map[string]bool{},
		macs:      map[string]string{"192.168.15.108": "80:8A:BD:0B:97:EC"},
	}
	service, repository := newTestService(t, driver)
	created, err := repository.Create(context.Background(), Device{
		ID: uuid.NewString(), Name: "TV", Kind: KindSmartTV,
		Host: "192.168.15.105", Port: DefaultPort, MAC: "80:8A:BD:0B:97:EC",
		SetupStatus: "ready", Enabled: true,
	}, "token")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	service.scan = func(context.Context, string, []int, time.Duration) []string { return nil }

	if _, err := service.Send(context.Background(), created.ID, "KEY_HOME"); err == nil {
		t.Fatal("esperava erro com a TV fora")
	}
	start := time.Now()
	if _, err := service.Send(context.Background(), created.ID, "KEY_HOME"); err == nil {
		t.Fatal("esperava erro com a TV fora")
	}
	if elapsed := time.Since(start); elapsed > 20*time.Millisecond {
		t.Fatalf("resposta com a TV offline demorou %s", elapsed)
	}
	if len(driver.sent) != 1 {
		t.Fatalf("a janela offline devia evitar novo dial: %v", driver.sent)
	}
}
