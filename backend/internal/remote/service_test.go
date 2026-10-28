package remote

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestNormalizeAddress(t *testing.T) {
	cases := []struct {
		raw      string
		port     int
		wantHost string
		wantPort int
		wantErr  bool
	}{
		{"192.168.0.10", 0, "192.168.0.10", DefaultPort, false},
		{"192.168.0.10:8001", 0, "192.168.0.10", 8001, false},
		{"wss://192.168.0.10:8002/api/v2/channels", 0, "192.168.0.10", 8002, false},
		{"http://tv.local", 0, "tv.local", DefaultPort, false},
		{"  10.0.0.5  ", 9000, "10.0.0.5", 9000, false},
		{"", 0, "", 0, true},
	}
	for _, tc := range cases {
		host, port, err := normalizeAddress(tc.raw, tc.port)
		if tc.wantErr {
			if err == nil {
				t.Fatalf("normalizeAddress(%q) expected error", tc.raw)
			}
			continue
		}
		if err != nil {
			t.Fatalf("normalizeAddress(%q) unexpected error: %v", tc.raw, err)
		}
		if host != tc.wantHost || port != tc.wantPort {
			t.Fatalf("normalizeAddress(%q) = %q:%d, want %q:%d", tc.raw, host, port, tc.wantHost, tc.wantPort)
		}
	}
}

func TestRemoteURLIncludesToken(t *testing.T) {
	without := channelURL("wss", "192.168.0.10:8002", controlChannelPath, "Valky", "")
	if without != "wss://192.168.0.10:8002/api/v2/channels/samsung.remote.control?name=VmFsa3k=" {
		t.Fatalf("unexpected url without token: %s", without)
	}
	with := channelURL("wss", "192.168.0.10:8002", controlChannelPath, "Valky", "abc123")
	if with != "wss://192.168.0.10:8002/api/v2/channels/samsung.remote.control?name=VmFsa3k=&token=abc123" {
		t.Fatalf("unexpected url with token: %s", with)
	}
}

func TestAllowedKeys(t *testing.T) {
	// validKey expects the canonical uppercase form; the service uppercases input.
	if !validKey("KEY_POWER") || !validKey("KEY_HOME") {
		t.Fatal("navigation keys must be allowed")
	}
	if validKey("key_home") {
		t.Fatal("lowercase keys must be normalized before validation")
	}
	if validKey("KEY_DROP_TABLE") {
		t.Fatal("unknown keys must be rejected")
	}
}

func TestSameMAC(t *testing.T) {
	if !sameMAC("80:8A:BD:0B:97:EC", "80-8a-bd-0b-97-ec") {
		t.Fatal("separador e caixa não podem mudar a identidade da TV")
	}
	if sameMAC("80:8A:BD:0B:97:EC", "80:8A:BD:0B:97:ED") {
		t.Fatal("MACs diferentes precisam ser rejeitados")
	}
	if sameMAC("", "80:8A:BD:0B:97:EC") {
		t.Fatal("MAC vazio não pode casar")
	}
}

func TestPickMACUsesActiveInterface(t *testing.T) {
	const wifi = "80:8A:BD:0B:97:EC"
	const wired = "80:8A:BD:0B:97:ED"
	cases := []struct {
		networkType string
		wifiMAC     string
		ethernetMAC string
		want        string
	}{
		{"wired", wifi, wired, wired},
		{"wireless", wifi, wired, wifi},
		{"wired", wifi, "", wifi},
		{"", wifi, wired, wifi},
		{"", "", wired, wired},
		{"", "", "", ""},
	}
	for _, tc := range cases {
		if got := pickMAC(tc.networkType, tc.wifiMAC, tc.ethernetMAC); got != tc.want {
			t.Fatalf("pickMAC(%q, %q, %q) = %q, want %q", tc.networkType, tc.wifiMAC, tc.ethernetMAC, got, tc.want)
		}
	}
}

func TestScanSubnetFindsOpenPort(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listener: %v", err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	found := scanSubnet(ctx, "127.0.0.1", []int{port}, 150*time.Millisecond)
	if len(found) != 1 || found[0] != "127.0.0.1" {
		t.Fatalf("scanSubnet = %v, want [127.0.0.1]", found)
	}
}

func TestLockRespectsCancellation(t *testing.T) {
	service := &Service{}
	unlock, err := service.lock(context.Background(), "tv")
	if err != nil {
		t.Fatalf("primeiro lock: %v", err)
	}
	defer unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	if _, err := service.lock(ctx, "tv"); err == nil {
		t.Fatal("espera pelo lock precisa respeitar o cancelamento do app")
	}
}

func TestOfflineWindow(t *testing.T) {
	service := &Service{}
	service.markOffline("tv", errors.New("TV desligada"))
	start := time.Now()
	err, ok := service.offlineNow("tv")
	if !ok || err.Error() != "TV desligada" {
		t.Fatalf("offlineNow = %v, %v", err, ok)
	}
	if elapsed := time.Since(start); elapsed > 20*time.Millisecond {
		t.Fatalf("resposta offline demorou %s", elapsed)
	}
	service.clearOffline("tv")
	if _, ok := service.offlineNow("tv"); ok {
		t.Fatal("clearOffline precisa liberar a próxima tentativa")
	}
}
