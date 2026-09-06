package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestCreateRuleEndpoint(t *testing.T) {
	database, err := store.Open(filepath.Join(t.TempDir(), "valkyris.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = database.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('camera-1','Nursery','10.0.0.3',x'01',x'01',x'01',?,?)`, now, now); err != nil {
		t.Fatal(err)
	}
	authManager := auth.NewManager(database, 10*time.Minute)
	session, err := authManager.BootstrapAdmin(context.Background(), auth.LoginRequest{Password: "a secure home password", DeviceName: "test"})
	if err != nil {
		t.Fatal(err)
	}
	service := rules.NewService(database)
	server := NewServer(authManager, nil, nil, nil, service, nil, nil, NewHub(), slog.New(slog.NewTextHandler(io.Discard, nil)))

	response := performJSON(t, server.Handler(), http.MethodPost, "/api/v1/rules", session.Token, rules.Rule{
		CameraID: "camera-1", Name: "Baby alert", DetectorTypes: []string{"baby_cry"},
		Confirmations: 2, CooldownSeconds: 60,
		Actions: rules.Actions{Record: true, Notify: true},
	})
	if response.Code != http.StatusCreated {
		t.Fatalf("create rule returned %d: %s", response.Code, response.Body.String())
	}
	var envelope struct {
		Success bool       `json:"success"`
		Data    rules.Rule `json:"data"`
	}
	if err = json.NewDecoder(response.Body).Decode(&envelope); err != nil {
		t.Fatal(err)
	}
	if !envelope.Success || envelope.Data.ID == "" || envelope.Data.CameraID != "camera-1" {
		t.Fatalf("unexpected create rule response: %+v", envelope)
	}
	// Advanced settings must survive the actual request/response boundary.
	response = performJSON(t, server.Handler(), http.MethodPost, "/api/v1/rules", session.Token, rules.Rule{
		CameraID: "camera-1", Name: "Berço à noite", DetectorTypes: []string{"motion"}, CooldownSeconds: 120,
		Schedule: rules.Schedule{Days: []int{1, 2}, Start: "22:00", End: "06:00", Timezone: "America/Sao_Paulo"},
		Motion:   &rules.MotionSettings{Region: rules.Region{X: .1, Y: .2, Width: .5, Height: .5}, MinDurationSeconds: 20, MinChangedFraction: .05},
		Actions:  rules.Actions{Record: true, Notify: true},
	})
	if response.Code != http.StatusCreated {
		t.Fatalf("advanced rule: %d %s", response.Code, response.Body.String())
	}
	if err = json.NewDecoder(response.Body).Decode(&envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Data.Motion == nil || envelope.Data.Motion.MinDurationSeconds != 20 || envelope.Data.Schedule.Start != "22:00" || envelope.Data.CooldownSeconds != 120 {
		t.Fatalf("lost advanced settings: %+v", envelope.Data)
	}

}
