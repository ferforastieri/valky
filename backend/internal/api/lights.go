package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
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

// lightPairingContext é o que o aplicativo conta ao abrir a janela de
// pareamento: a rede que ele vai enviar e a própria versão. Nunca senha.
type lightPairingContext struct {
	Network string `json:"network"`
	App     string `json:"app"`
	Android int    `json:"android"`
}

func pairingContextSummary(in lightPairingContext) string {
	partes := []string{}
	if in.App != "" {
		partes = append(partes, fmt.Sprintf("aplicativo %s (android %d)", in.App, in.Android))
	}
	if in.Network != "" {
		partes = append(partes, "rede escolhida: "+in.Network)
	}
	return strings.Join(partes, "; ")
}

func (s *Server) startLightPairing(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.lights.Scanner(); !ok {
		writeError(w, http.StatusServiceUnavailable, light.ErrNoLightingAdapter)
		return
	}
	var contexto lightPairingContext
	if r.ContentLength > 0 {
		// Contexto opcional: um corpo estranho não impede o pareamento.
		_ = json.NewDecoder(r.Body).Decode(&contexto)
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
	// O diagnóstico fica no banco, na sessão — não vira registro de servidor.
	if resumo := pairingContextSummary(contexto); resumo != "" {
		_ = s.lights.RecordPairingDiagnostic(r.Context(), session.ID, resumo)
	}
	writeSuccess(w, http.StatusCreated, "Pairing window open", session)
}

func (s *Server) lightPairingStatus(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	session, err := s.lights.PairingStatus(id)
	if err != nil {
		writeError(w, http.StatusNotFound, err)
		return
	}
	// O aplicativo aproveita a consulta de estado para deixar o que viu
	// (códigos que a lâmpada reportou, redes): vai para o diagnóstico da
	// sessão, no banco.
	if relato := strings.TrimSpace(r.URL.Query().Get("relato")); relato != "" {
		_ = s.lights.RecordPairingDiagnostic(r.Context(), id, relato)
	}
	writeSuccess(w, http.StatusOK, "Pairing status", session)
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
