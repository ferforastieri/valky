package tuya

import (
	"reflect"
	"testing"
)

// Vetores da referência comunitária do formato, os mesmos usados no aplicativo:
// garantem que a transmissão do servidor fala exatamente a mesma língua.
const (
	smartConfigSSID       = "CasaBoa"
	smartConfigPassword   = "senha123"
	smartConfigTokenGroup = "AYAbCdEfGh12345678pr_0"
)

func TestSmartConfigTokenGroup(t *testing.T) {
	if got := SmartConfigTokenGroup("AY", "AbCdEfGh12345678", "pr_0"); got != smartConfigTokenGroup {
		t.Fatalf("token group = %q", got)
	}
}

func TestSmartConfigMulticastHeadIsFixed(t *testing.T) {
	want := []string{"226.120.89.84", "226.121.84.83", "226.122.49.48"}
	if got := smartConfigMulticastHead(); !reflect.DeepEqual(got, want) {
		t.Fatalf("head = %v", got)
	}
}

func TestSmartConfigBroadcastBodyMatchesReference(t *testing.T) {
	want := []int{
		18, 39, 58, 64, 200, 128, 264, 371, 357, 366, 164, 129, 360, 353, 305, 306, 192, 130,
		307, 278, 321, 345, 129, 131, 321, 354, 323, 356, 216, 132, 325, 358, 327, 360, 163, 133,
		305, 306, 307, 308, 229, 134, 309, 310, 311, 312, 136, 135, 368, 370, 351, 304, 128, 136,
		323, 353, 371, 353, 240, 137, 322, 367, 353, 256, 256, 256,
	}
	got, _ := SmartConfigFrames(smartConfigSSID, smartConfigPassword, smartConfigTokenGroup)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("broadcast body mismatch\n got: %v\nwant: %v", got, want)
	}
}

func TestSmartConfigMulticastBodyMatchesReference(t *testing.T) {
	_, body := SmartConfigFrames(smartConfigSSID, smartConfigPassword, smartConfigTokenGroup)
	if len(body) == 0 {
		t.Fatal("empty multicast body")
	}
	wantHead := []string{"226.64.7.7", "226.65.25.20", "226.66.189.1"}
	if !reflect.DeepEqual(body[:3], wantHead) {
		t.Fatalf("multicast head = %v", body[:3])
	}
	// A senha começa na sequência 0, com tamanho, CRC e o bloco cifrado.
	passwordStart := -1
	for index, address := range body {
		if address == "226.0.8.8" {
			passwordStart = index
			break
		}
	}
	if passwordStart < 0 {
		t.Fatal("password block not found")
	}
	wantPassword := []string{"226.0.8.8", "226.1.82.88", "226.2.241.92", "226.3.112.222", "226.4.252.240"}
	if !reflect.DeepEqual(body[passwordStart:passwordStart+5], wantPassword) {
		t.Fatalf("password block = %v", body[passwordStart:passwordStart+5])
	}
	if ParseMulticastSequence(body[passwordStart]) != 0 {
		t.Fatal("password should start at sequence 0")
	}
	foundToken := false
	for _, address := range body {
		if ParseMulticastSequence(address) == 32 {
			foundToken = true
			break
		}
	}
	if !foundToken {
		t.Fatal("token group should start at sequence 32")
	}
}

func TestSmartConfigPasswordUsesTheVendorKey(t *testing.T) {
	want := []int{222, 112, 240, 252, 151, 115, 97, 14, 180, 2, 72, 92, 221, 220, 66, 124}
	encrypted := smartConfigEncryptPassword(smartConfigPassword)
	got := make([]int, 0, len(encrypted))
	for _, value := range encrypted {
		got = append(got, int(value))
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("encrypted password = %v", got)
	}
}
