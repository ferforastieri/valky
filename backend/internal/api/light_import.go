package api

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/ferforastieri/valky/backend/internal/light/tuyaimport"
)

// lightImporter is the Tuya Cloud import flow. The concrete manager holds the
// QR session in memory; the interface exists so the API tests can drive it
// without calling the vendor.
type lightImporter interface {
	Start(ctx context.Context, userCode string) (tuyaimport.StartResult, error)
	Poll(ctx context.Context, id string) (tuyaimport.Status, error)
	Apply(ctx context.Context, id string, deviceIDs []string) (tuyaimport.ApplyResult, error)
	Cancel(id string)
}

type tuyaImportStartInput struct {
	UserCode string `json:"userCode"`
}

type tuyaImportApplyInput struct {
	DeviceIDs []string `json:"deviceIds"`
}

// startLightImport begins the QR authorization. The local key never goes to the
// app: it stays in the backend session and is used by the existing local
// registration flow when the administrator confirms the devices.
func (s *Server) startLightImport(w http.ResponseWriter, r *http.Request) {
	if s.lightImport == nil {
		writeError(w, http.StatusServiceUnavailable, errors.New("importador da Tuya indisponível"))
		return
	}
	var in tuyaImportStartInput
	if !decode(w, r, &in) {
		return
	}
	if strings.TrimSpace(in.UserCode) == "" {
		writeError(w, http.StatusBadRequest, errors.New("informe o User Code do aplicativo Tuya"))
		return
	}
	result, err := s.lightImport.Start(r.Context(), in.UserCode)
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Tuya import session started", result)
}

func (s *Server) lightImportStatus(w http.ResponseWriter, r *http.Request) {
	if s.lightImport == nil {
		writeError(w, http.StatusServiceUnavailable, errors.New("importador da Tuya indisponível"))
		return
	}
	status, err := s.lightImport.Poll(r.Context(), r.PathValue("session"))
	if errors.Is(err, tuyaimport.ErrSessionNotFound) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Tuya import status", status)
}

func (s *Server) applyLightImport(w http.ResponseWriter, r *http.Request) {
	if s.lightImport == nil {
		writeError(w, http.StatusServiceUnavailable, errors.New("importador da Tuya indisponível"))
		return
	}
	var in tuyaImportApplyInput
	if !decode(w, r, &in) {
		return
	}
	if len(in.DeviceIDs) == 0 {
		writeError(w, http.StatusBadRequest, tuyaimport.ErrNoDevices)
		return
	}
	result, err := s.lightImport.Apply(r.Context(), r.PathValue("session"), in.DeviceIDs)
	switch {
	case errors.Is(err, tuyaimport.ErrSessionNotFound):
		writeError(w, http.StatusNotFound, err)
	case errors.Is(err, tuyaimport.ErrNotAuthorized), errors.Is(err, tuyaimport.ErrNoDevices):
		writeError(w, http.StatusBadRequest, err)
	case err != nil:
		writeError(w, http.StatusConflict, err)
	default:
		writeSuccess(w, http.StatusOK, "Tuya import finished", result)
	}
}

func (s *Server) cancelLightImport(w http.ResponseWriter, r *http.Request) {
	if s.lightImport == nil {
		writeError(w, http.StatusServiceUnavailable, errors.New("importador da Tuya indisponível"))
		return
	}
	session := r.PathValue("session")
	s.lightImport.Cancel(session)
	writeSuccess(w, http.StatusOK, "Tuya import canceled", map[string]string{"sessionId": session})
}
