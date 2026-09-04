package camera

import (
	"context"
	"net/url"
	"testing"

	valkyriscrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestCreatePendingBuildsTapoRTSPURIAndPersistsIcon(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/valkyris.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	vault, err := valkyriscrypto.LoadOrCreate(t.TempDir() + "/master.key")
	if err != nil {
		t.Fatal(err)
	}
	repository := NewRepository(db, vault)
	created, err := repository.CreatePending(context.Background(), CreateInput{
		Name: "Quarto do bebê", Icon: "baby", Host: "192.0.2.40",
		Username: "camera@example.com", Password: "p@ss:/word",
	})
	if err != nil {
		t.Fatal(err)
	}
	loaded, credentials, err := repository.Get(context.Background(), created.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Icon != "baby" {
		t.Fatalf("icon = %q, want baby", loaded.Icon)
	}
	stream, err := url.Parse(credentials.RTSPURI)
	if err != nil {
		t.Fatal(err)
	}
	password, _ := stream.User.Password()
	if stream.Scheme != "rtsp" || stream.Host != "192.0.2.40:554" || stream.Path != "/stream1" || stream.User.Username() != "camera@example.com" || password != "p@ss:/word" {
		t.Fatalf("unexpected generated RTSP URI: %s", credentials.RTSPURI)
	}
}

func TestNormalizeIconFallsBackToCamera(t *testing.T) {
	if got := normalizeIcon("unknown"); got != "camera" {
		t.Fatalf("normalizeIcon = %q, want camera", got)
	}
}
