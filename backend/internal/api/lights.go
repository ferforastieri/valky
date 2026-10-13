package api

import (
	"database/sql"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"

	"github.com/ferforastieri/valkyris/backend/internal/light"
)

func (s *Server) listLights(w http.ResponseWriter, r *http.Request) {
	items, err := s.lights.List(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Lights listed", items)
}

func (s *Server) getLight(w http.ResponseWriter, r *http.Request) {
	item, err := s.lights.Get(r.Context(), r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Light loaded", item)
}

func (s *Server) deleteLight(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.lights.Delete(r.Context(), id); errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	} else if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	s.hub.Broadcast(map[string]any{"type": "light.deleted", "lightId": id})
	writeSuccess(w, http.StatusOK, "Light deleted", map[string]string{"id": id})
}

// discoverLights looks for candidate devices on the local network so the app can
// offer them for registration. Discovery never leaves the local subnets.
func (s *Server) discoverLights(w http.ResponseWriter, r *http.Request) {
	var in light.DiscoveryInput
	if r.ContentLength > 0 && !decode(w, r, &in) {
		return
	}
	if _, ok := s.lights.Scanner(); !ok {
		writeError(w, http.StatusServiceUnavailable, light.ErrNoLightingAdapter)
		return
	}
	candidates, err := s.lights.Scan(r.Context(), in)
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Local lighting discovery finished", candidates)
}

// startLightPairing opens a local pairing window. The phone sends the Wi-Fi
// credentials to the lamp over the air; the backend only supplies the one-time
// values and then watches the network for the new device.
func (s *Server) startLightPairing(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.lights.Scanner(); !ok {
		writeError(w, http.StatusServiceUnavailable, light.ErrNoLightingAdapter)
		return
	}
	session, err := s.lights.StartPairing(r.Context())
	if errors.Is(err, light.ErrPairingInProgress) {
		writeSuccess(w, http.StatusOK, "Pairing already in progress", session)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Pairing window open", session)
}

func (s *Server) lightPairingStatus(w http.ResponseWriter, r *http.Request) {
	session, err := s.lights.PairingStatus(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Pairing status", session)
}

// registerLightAnnouncement recebe o que o celular ouviu no ar. O servidor não
// escuta o Wi-Fi, então quem captura o anúncio da lâmpada é o celular; aqui o
// anúncio é decodificado e, se trouxer identificador, a lâmpada é adotada.
func (s *Server) registerLightAnnouncement(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Address string `json:"address"`
		Port    int    `json:"port"`
		Payload string `json:"payload"`
	}
	if !decode(w, r, &in) {
		return
	}
	raw, err := hex.DecodeString(strings.TrimSpace(in.Payload))
	if err != nil || len(raw) == 0 {
		writeError(w, http.StatusBadRequest, errors.New("anúncio inválido"))
		return
	}
	if strings.TrimSpace(in.Address) == "" {
		writeError(w, http.StatusBadRequest, errors.New("informe o endereço de origem do anúncio"))
		return
	}
	session, err := s.lights.RegisterAnnouncement(r.Context(), r.PathValue("id"), in.Address, in.Port, raw)
	if err != nil {
		writeError(w, http.StatusNotFound, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Announcement registered", session)
}

func (s *Server) cancelLightPairing(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.lights.CancelPairing(id); err != nil {
		writeError(w, http.StatusNotFound, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Pairing cancelled", map[string]string{"id": id})
}

func (s *Server) updateLight(w http.ResponseWriter, r *http.Request) {
	var in light.UpdateInput
	if !decode(w, r, &in) {
		return
	}
	item, err := s.lights.Update(r.Context(), r.PathValue("id"), in)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Light updated", item)
}

func (s *Server) controlLight(w http.ResponseWriter, r *http.Request) {
	var patch light.StatePatch
	if !decode(w, r, &patch) {
		return
	}
	item, err := s.lights.Control(r.Context(), r.PathValue("id"), patch)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Light controlled", item)
}
