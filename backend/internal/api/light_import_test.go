package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ferforastieri/valky/backend/internal/light/tuyaimport"
)

type fakeLightImporter struct {
	start       tuyaimport.StartResult
	startErr    error
	status      tuyaimport.Status
	statusErr   error
	apply       tuyaimport.ApplyResult
	applyErr    error
	canceled    string
	lastDevices []string
}

func (f *fakeLightImporter) Start(context.Context, string) (tuyaimport.StartResult, error) {
	return f.start, f.startErr
}
func (f *fakeLightImporter) Poll(context.Context, string) (tuyaimport.Status, error) {
	return f.status, f.statusErr
}
func (f *fakeLightImporter) Apply(_ context.Context, _ string, devices []string) (tuyaimport.ApplyResult, error) {
	f.lastDevices = devices
	return f.apply, f.applyErr
}
func (f *fakeLightImporter) Cancel(session string) { f.canceled = session }

func TestStartLightImport(t *testing.T) {
	importer := &fakeLightImporter{start: tuyaimport.StartResult{SessionID: "s1", QRContent: "tuyaSmart--qrLogin?token=t"}}
	server := &Server{lightImport: importer}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/lights/import/tuya", strings.NewReader(`{"userCode":"123456"}`))
	res := httptest.NewRecorder()
	server.startLightImport(res, req)
	if res.Code != http.StatusCreated || !strings.Contains(res.Body.String(), "s1") {
		t.Fatalf("início = %d %s", res.Code, res.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/api/v1/lights/import/tuya", strings.NewReader(`{"userCode":""}`))
	res = httptest.NewRecorder()
	server.startLightImport(res, req)
	if res.Code != http.StatusBadRequest {
		t.Fatalf("User Code vazio = %d", res.Code)
	}
}

func TestLightImportStatusAndApply(t *testing.T) {
	importer := &fakeLightImporter{
		status: tuyaimport.Status{Status: "authorized", Devices: []tuyaimport.Device{{DeviceID: "dev-1", Name: "Sala", HasLocalKey: true}}},
		apply:  tuyaimport.ApplyResult{Imported: []tuyaimport.Imported{{DeviceID: "dev-1"}}},
	}
	server := &Server{lightImport: importer}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/lights/import/tuya/s1", nil)
	req.SetPathValue("session", "s1")
	res := httptest.NewRecorder()
	server.lightImportStatus(res, req)
	if res.Code != http.StatusOK || !strings.Contains(res.Body.String(), "dev-1") {
		t.Fatalf("status = %d %s", res.Code, res.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/api/v1/lights/import/tuya/s1/apply", strings.NewReader(`{"deviceIds":["dev-1"]}`))
	req.SetPathValue("session", "s1")
	res = httptest.NewRecorder()
	server.applyLightImport(res, req)
	if res.Code != http.StatusOK || len(importer.lastDevices) != 1 || importer.lastDevices[0] != "dev-1" {
		t.Fatalf("apply = %d %s (devices %v)", res.Code, res.Body.String(), importer.lastDevices)
	}

	importer.applyErr = tuyaimport.ErrNotAuthorized
	req = httptest.NewRequest(http.MethodPost, "/api/v1/lights/import/tuya/s1/apply", strings.NewReader(`{"deviceIds":["dev-1"]}`))
	req.SetPathValue("session", "s1")
	res = httptest.NewRecorder()
	server.applyLightImport(res, req)
	if res.Code != http.StatusBadRequest {
		t.Fatalf("apply sem autorização = %d", res.Code)
	}
}

func TestLightImportNotFoundAndCancel(t *testing.T) {
	importer := &fakeLightImporter{statusErr: tuyaimport.ErrSessionNotFound}
	server := &Server{lightImport: importer}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/lights/import/tuya/nope", nil)
	req.SetPathValue("session", "nope")
	res := httptest.NewRecorder()
	server.lightImportStatus(res, req)
	if res.Code != http.StatusNotFound {
		t.Fatalf("sessão inexistente = %d", res.Code)
	}

	req = httptest.NewRequest(http.MethodDelete, "/api/v1/lights/import/tuya/s1", nil)
	req.SetPathValue("session", "s1")
	res = httptest.NewRecorder()
	server.cancelLightImport(res, req)
	if res.Code != http.StatusOK || importer.canceled != "s1" {
		t.Fatalf("cancelar = %d %q", res.Code, importer.canceled)
	}
}

func TestLightImportUnavailable(t *testing.T) {
	server := &Server{}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/lights/import/tuya", strings.NewReader(`{"userCode":"123456"}`))
	res := httptest.NewRecorder()
	server.startLightImport(res, req)
	if res.Code != http.StatusServiceUnavailable {
		t.Fatalf("sem importer = %d", res.Code)
	}
}
