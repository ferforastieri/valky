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

func (s *Server) createLight(w http.ResponseWriter, r *http.Request) {
	var input light.CreateInput
	if !decode(w, r, &input) {
		return
	}
	item, err := s.lights.Create(r.Context(), input)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	s.hub.Broadcast(map[string]any{"type": "light.created", "light": item})
	writeSuccess(w, http.StatusCreated, "Light created", item)
}

func (s *Server) updateLight(w http.ResponseWriter, r *http.Request) {
	var input light.UpdateInput
	if !decode(w, r, &input) {
		return
	}
	item, err := s.lights.Update(r.Context(), r.PathValue("id"), input)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	s.hub.Broadcast(map[string]any{"type": "light.updated", "light": item})
	writeSuccess(w, http.StatusOK, "Light updated", item)
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

func (s *Server) testLight(w http.ResponseWriter, r *http.Request) {
	item, err := s.lights.Refresh(r.Context(), r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	s.hub.Broadcast(map[string]any{"type": "light.updated", "light": item})
	writeSuccess(w, http.StatusOK, "Light connection tested", item)
}
