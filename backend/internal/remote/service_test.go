package remote

import "testing"

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
