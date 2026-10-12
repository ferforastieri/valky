package api

import (
	"database/sql"
	"errors"
	"net/http"

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

func (s *Server) createLight(w http.ResponseWriter, r *http.Request) {
	var in light.CreateInput
	if !decode(w, r, &in) {
		return
	}
	if _, ok := s.lights.Scanner(); !ok {
		writeError(w, http.StatusServiceUnavailable, light.ErrNoLightingAdapter)
		return
	}
	item, err := s.lights.Create(r.Context(), in)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Light registered", item)
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
