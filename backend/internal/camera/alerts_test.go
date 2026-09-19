package camera

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestAlertPresentationDefaultsAndPersistence(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/alerts.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	vault, err := appcrypto.LoadOrCreate(t.TempDir() + "/key")
	if err != nil {
		t.Fatal(err)
	}
	repo := NewRepository(db, vault)
	ctx := context.Background()
	c, err := repo.Create(ctx, CreateInput{Name: "Nursery", Host: "camera", Username: "user", Password: "secret"}, Capabilities{}, "", ServiceAddresses{})
	if err != nil {
		t.Fatal(err)
	}
	if c.Alerts != DefaultAlertPresentation() {
		t.Fatalf("defaults: %+v", c.Alerts)
	}
	var input UpdateInput
	// Kotlin omits default values, including sound/vibration/fullScreen.
	if err := json.Unmarshal([]byte(`{"name":"Nursery","host":"camera","alerts":{"alarmTitle":"Baby needs you"}}`), &input); err != nil {
		t.Fatal(err)
	}
	updated, changed, err := repo.Update(ctx, c.ID, input)
	if err != nil || changed {
		t.Fatalf("presentation must not reconnect camera: %v %v", changed, err)
	}
	if updated.Alerts.AlarmTitle != "Baby needs you" || !updated.Alerts.Vibrate || !updated.Alerts.FullScreen || updated.Alerts.AlarmSound != "alarm" {
		t.Fatalf("settings: %+v", updated.Alerts)
	}
	input.Alerts = nil
	updated, _, err = repo.Update(ctx, c.ID, input)
	if err != nil || updated.Alerts.AlarmTitle != "Baby needs you" {
		t.Fatalf("legacy update lost settings: %+v %v", updated.Alerts, err)
	}
	silent := AlertPresentation{AlarmSound: "silent"}
	input.Alerts = &silent
	updated, _, err = repo.Update(ctx, c.ID, input)
	if err != nil || updated.Alerts != silent {
		t.Fatalf("false values lost: %+v %v", updated.Alerts, err)
	}
	list, err := repo.List(ctx)
	if err != nil || len(list) != 1 || list[0].Alerts != silent {
		t.Fatalf("list: %+v %v", list, err)
	}
	// Explicit empty object restores defaults; omission preserves settings.
	if err := json.Unmarshal([]byte(`{"name":"Nursery","host":"camera","alerts":{}}`), &input); err != nil {
		t.Fatal(err)
	}
	updated, _, err = repo.Update(ctx, c.ID, input)
	if err != nil || updated.Alerts != DefaultAlertPresentation() {
		t.Fatalf("reset: %+v %v", updated.Alerts, err)
	}
	if _, err := db.DB.Exec(`UPDATE cameras SET alerts_json='{}'`); err != nil {
		t.Fatal(err)
	}
	updated, _, err = repo.Get(ctx, c.ID)
	if err != nil || updated.Alerts != DefaultAlertPresentation() {
		t.Fatalf("migration defaults: %+v %v", updated.Alerts, err)
	}
}

func TestAlertPresentationRejectsOversizedOrInvalidSettings(t *testing.T) {
	for _, a := range []AlertPresentation{
		{AlarmSound: "unknown"},
		{AlarmSound: "alarm", AlarmTitle: strings.Repeat("a", 81)},
		{AlarmSound: "alarm", NotificationBody: strings.Repeat("a", 241)},
		{AlarmSound: "alarm", AlarmBody: strings.Repeat("😀", 240), NotificationBody: strings.Repeat("😀", 240)},
	} {
		if a.Validate() == nil {
			t.Fatalf("accepted invalid settings: %+v", a)
		}
	}
}
