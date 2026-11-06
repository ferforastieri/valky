package api

import (
	"context"
	"testing"

	"github.com/ferforastieri/valky/backend/internal/camera"
)

// A ronda não pode ignorar para sempre a câmera cadastrada antes do campo de
// identidade existir: sem MAC nem série ela jamais seria redescoberta, que é
// justamente o cadastro antigo de quem mais precisa da correção.
func TestDiscoverMissingCamerasCapturesIdentityOfLegacyCamera(t *testing.T) {
	server, repository, _ := newDiscoveryServer(t)
	cam, err := repository.CreatePending(context.Background(), camera.CreateInput{Name: "Quarto", Host: "192.168.15.103", Username: "camera", Password: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	// Endereço salvo responde: é a oportunidade de ler a identidade sem
	// precisar varrer a faixa.
	server.cameraReachable = func(context.Context, string, int) bool { return true }
	server.cameraIdentity = func(context.Context, string, int, string, string) (string, string) {
		return "SERIAL-QR-1", "98:03:8e:b5:31:f7"
	}

	server.discoverMissingCameras(context.Background())

	updated, _, err := repository.Get(context.Background(), cam.ID)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Serial != "SERIAL-QR-1" || updated.MAC != "98:03:8e:b5:31:f7" {
		t.Fatalf("identidade deveria ter sido capturada, veio mac=%q serial=%q", updated.MAC, updated.Serial)
	}
	if updated.Host != "192.168.15.103" {
		t.Fatalf("o endereço não deveria mudar só por capturar a identidade, veio %q", updated.Host)
	}
}

// Câmera sem identidade e com o endereço fora do ar não deve gravar nada.
func TestDiscoverMissingCamerasIgnoresUnreachableLegacyCamera(t *testing.T) {
	server, repository, _ := newDiscoveryServer(t)
	cam, err := repository.CreatePending(context.Background(), camera.CreateInput{Name: "Quarto", Host: "192.168.15.103", Username: "camera", Password: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	server.cameraReachable = func(context.Context, string, int) bool { return false }
	server.cameraIdentity = func(context.Context, string, int, string, string) (string, string) {
		t.Fatal("não deveria sondar identidade de endereço que não responde")
		return "", ""
	}

	server.discoverMissingCameras(context.Background())

	updated, _, err := repository.Get(context.Background(), cam.ID)
	if err != nil {
		t.Fatal(err)
	}
	if camera.Identity(updated.MAC, updated.Serial) {
		t.Fatalf("nada deveria ter sido gravado, veio mac=%q serial=%q", updated.MAC, updated.Serial)
	}
}
