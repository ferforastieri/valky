package notify

import (
	"context"
	"testing"
	"time"

	"github.com/ferforastieri/valky/backend/internal/camera"
	appcrypto "github.com/ferforastieri/valky/backend/internal/crypto"
	"github.com/ferforastieri/valky/backend/internal/event"
	"github.com/ferforastieri/valky/backend/internal/rules"
	"github.com/ferforastieri/valky/backend/internal/store"
)

// Uma regra com destinatários escolhidos avisa só essas pessoas; sem lista
// definida, a família inteira continua recebendo.
func TestRecipientsFilterSelectsWhoReceives(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/recipients.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	vault, err := appcrypto.LoadOrCreate(t.TempDir() + "/master.key")
	if err != nil {
		t.Fatal(err)
	}
	service := NewService(db, vault, "")
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('camera','Door','10.0.0.2',x'01',x'01',x'01',?,?)`, now, now); err != nil {
		t.Fatal(err)
	}
	endpoint, err := vault.EncryptString("fcm-token")
	if err != nil {
		t.Fatal(err)
	}
	secret, err := vault.EncryptString("device-secret")
	if err != nil {
		t.Fatal(err)
	}
	for index, person := range []struct{ id, device string }{{"ana", "phone-ana"}, {"bruno", "phone-bruno"}} {
		if _, err = db.DB.Exec(`INSERT INTO users(id,name,created_at,updated_at) VALUES(?,?,?,?)`, person.id, person.id, now, now); err != nil {
			t.Fatal(err)
		}
		tokenHash := []byte{byte(index + 1)}
		if _, err = db.DB.Exec(`INSERT INTO devices(id,user_id,name,token_hash,push_endpoint_enc,push_secret_enc,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?)`, person.device, person.id, person.id, tokenHash, endpoint, secret, now, now); err != nil {
			t.Fatal(err)
		}
	}
	alerts := camera.DefaultAlertPresentation()
	rule, err := rules.NewService(db).Create(context.Background(), rules.Rule{CameraID: "camera", Name: "Motion", DetectorTypes: []string{"motion"}, Actions: rules.Actions{Alerts: &alerts}})
	if err != nil {
		t.Fatal(err)
	}
	created, err := event.NewService(db).Create(context.Background(), event.Event{CameraID: "camera", RuleID: &rule.ID, Type: "motion", Confidence: .8})
	if err != nil {
		t.Fatal(err)
	}
	countDeliveries := func() int {
		t.Helper()
		var total int
		if err = db.DB.QueryRow(`SELECT COUNT(*) FROM push_deliveries`).Scan(&total); err != nil {
			t.Fatal(err)
		}
		return total
	}

	if err = service.Enqueue(context.Background(), created); err != nil {
		t.Fatal(err)
	}
	if got := countDeliveries(); got != 2 {
		t.Fatalf("sem destinatários definidos a família inteira deve receber, veio %d", got)
	}
	if _, err = db.DB.Exec(`DELETE FROM push_deliveries`); err != nil {
		t.Fatal(err)
	}

	if _, err = rules.NewService(db).UpdateRecipients(context.Background(), rule.ID, []string{"ana"}); err != nil {
		t.Fatal(err)
	}
	if err = service.Enqueue(context.Background(), created); err != nil {
		t.Fatal(err)
	}
	var only string
	if err = db.DB.QueryRow(`SELECT device_id FROM push_deliveries`).Scan(&only); err != nil {
		t.Fatal(err)
	}
	if only != "phone-ana" {
		t.Fatalf("só a Ana deveria receber, veio %s", only)
	}
	if _, err = db.DB.Exec(`DELETE FROM push_deliveries`); err != nil {
		t.Fatal(err)
	}

	if _, err = rules.NewService(db).UpdateRecipients(context.Background(), rule.ID, []string{}); err != nil {
		t.Fatal(err)
	}
	if err = service.Enqueue(context.Background(), created); err != nil {
		t.Fatal(err)
	}
	if got := countDeliveries(); got != 0 {
		t.Fatalf("lista vazia não avisa ninguém, veio %d", got)
	}
}

// Editar a regra sem falar de destinatários não pode apagar quem foi escolhido.
func TestUpdateRuleKeepsRecipients(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/rules.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('camera','Door','10.0.0.2',x'01',x'01',x'01',?,?)`, now, now); err != nil {
		t.Fatal(err)
	}
	alerts := camera.DefaultAlertPresentation()
	service := rules.NewService(db)
	rule, err := service.Create(context.Background(), rules.Rule{CameraID: "camera", Name: "Motion", DetectorTypes: []string{"motion"}, Actions: rules.Actions{Alerts: &alerts}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.UpdateRecipients(context.Background(), rule.ID, []string{"ana"}); err != nil {
		t.Fatal(err)
	}
	edited := rule
	edited.Name = "Motion and person"
	edited.Actions.Alerts = nil
	edited.Actions.Recipients = rules.Recipients{}
	if _, err = service.Update(context.Background(), rule.ID, edited); err != nil {
		t.Fatal(err)
	}
	stored, err := service.List(context.Background(), "camera")
	if err != nil {
		t.Fatal(err)
	}
	if len(stored) != 1 {
		t.Fatalf("esperava uma regra, veio %d", len(stored))
	}
	if len(stored[0].Actions.Recipients.Value) != 1 || stored[0].Actions.Recipients.Value[0] != "ana" {
		t.Fatalf("destinatários deveriam ser preservados, veio %v", stored[0].Actions.Recipients.Value)
	}
}
