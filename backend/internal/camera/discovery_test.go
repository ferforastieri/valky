package camera

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	appcrypto "github.com/ferforastieri/valky/backend/internal/crypto"
	"github.com/ferforastieri/valky/backend/internal/store"
)

// fakeCamera responde ONVIF com a identidade combinada e RTSP na porta 554
// simulada, para provar que a ronda reconhece a câmera em outro endereço.
func fakeCamera(t *testing.T, serial, mac string) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		switch {
		case strings.Contains(string(body), "GetDeviceInformation"):
			fmt.Fprintf(w, `<s:Envelope><s:Body><GetDeviceInformationResponse><SerialNumber>%s</SerialNumber></GetDeviceInformationResponse></s:Body></s:Envelope>`, serial)
		case strings.Contains(string(body), "GetNetworkInterfaces"):
			fmt.Fprintf(w, `<s:Envelope><s:Body><GetNetworkInterfacesResponse><HwAddress>%s</HwAddress></GetNetworkInterfacesResponse></s:Body></s:Envelope>`, mac)
		default:
			http.Error(w, "unexpected", http.StatusBadRequest)
		}
	}))
	t.Cleanup(server.Close)
}

func TestSameIdentityIgnoresSeparators(t *testing.T) {
	if !sameIdentity("98:03:8E:B5:31:F7", "98-03-8e-b5-31-f7") {
		t.Fatal("mesmo MAC com separadores diferentes deveria conferir")
	}
	if sameIdentity("98:03:8E:B5:31:F7", "98:03:8E:B5:31:F8") {
		t.Fatal("MAC diferente não pode conferir")
	}
	if sameIdentity("", "") {
		t.Fatal("identidade vazia não pode conferir")
	}
}

func TestIdentityRequiresAnyIdentifier(t *testing.T) {
	if Identity("", "") {
		t.Fatal("sem MAC e sem série não há como provar que o endereço é da câmera")
	}
	if !Identity("98:03:8E:B5:31:F7", "") || !Identity("", "ABC123") {
		t.Fatal("MAC ou série sozinhos já identificam a câmera")
	}
}

func TestScanPortFindsOpenPort(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	go func() {
		for {
			conn, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			_ = conn.Close()
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	found := ScanPort(ctx, "127.0.0.1", port, 100*time.Millisecond)
	if len(found) != 1 || found[0] != "127.0.0.1" {
		t.Fatalf("varredura deveria achar o próprio endereço: %v", found)
	}
}

// TestDiscoverRejectsCandidateWithoutMatchingIdentity garante o mais importante:
// achar uma câmera na faixa não autoriza gravar o endereço dela no cadastro.
func TestDiscoverRejectsCandidateWithoutMatchingIdentity(t *testing.T) {
	fakeCamera(t, "OTHER-SERIAL", "AA:BB:CC:DD:EE:FF")
	client := NewONVIFClient()
	scan := func(context.Context, string, int, time.Duration) []string { return []string{"10.0.0.9"} }
	result := client.Discover(context.Background(), Camera{ID: "camera-1", Host: "10.0.0.9", Port: 2020, MAC: "98:03:8E:B5:31:F7"}, Credentials{}, scan)
	if result.Found {
		t.Fatalf("identidade diferente não pode ser aceita: %+v", result)
	}
}

func TestSetHostRebuildsRTSPURI(t *testing.T) {
	directory := t.TempDir()
	database, err := store.Open(filepath.Join(directory, "valky.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	vault, err := appcrypto.LoadOrCreate(filepath.Join(directory, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	repository := NewRepository(database, vault)
	cam, err := repository.CreatePending(context.Background(), CreateInput{
		Name: "Entrada", Host: "192.168.15.50", Port: 2020, Username: "camera", Password: "secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	_, before, err := repository.Get(context.Background(), cam.ID)
	if err != nil {
		t.Fatal(err)
	}
	updated, credentials, err := repository.SetHost(context.Background(), cam.ID, "192.168.15.103", 2020)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Host != "192.168.15.103" {
		t.Fatalf("endereço não foi atualizado: %q", updated.Host)
	}
	if !strings.Contains(credentials.RTSPURI, "192.168.15.103:554") {
		t.Fatalf("a URI RTSP precisa acompanhar o endereço novo: %q", credentials.RTSPURI)
	}
	if strings.Contains(before.RTSPURI, "192.168.15.103") {
		t.Fatal("a URI antiga não deveria conter o endereço novo")
	}
	if !strings.HasSuffix(credentials.RTSPURI, "/stream1") {
		t.Fatalf("o caminho RTSP deve ser preservado: %q", credentials.RTSPURI)
	}
}

func TestSetIdentityIsPersisted(t *testing.T) {
	directory := t.TempDir()
	database, err := store.Open(filepath.Join(directory, "valky.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	vault, err := appcrypto.LoadOrCreate(filepath.Join(directory, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	repository := NewRepository(database, vault)
	cam, err := repository.CreatePending(context.Background(), CreateInput{Name: "Entrada", Host: "10.0.0.2", Username: "u", Password: "p"})
	if err != nil {
		t.Fatal(err)
	}
	if err = repository.SetIdentity(context.Background(), cam.ID, "98:03:8E:B5:31:F7", "SERIAL-1"); err != nil {
		t.Fatal(err)
	}
	loaded, _, err := repository.Get(context.Background(), cam.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.MAC != "98:03:8E:B5:31:F7" || loaded.Serial != "SERIAL-1" {
		t.Fatalf("identidade não foi gravada: %+v", loaded)
	}
	if strconv.Itoa(loaded.Port) != "2020" {
		t.Fatalf("a porta padrão deveria ser 2020: %d", loaded.Port)
	}
}
