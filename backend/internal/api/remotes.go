package api

import (
	"database/sql"
	"errors"
	"net/http"

	"github.com/ferforastieri/valkyris/backend/internal/remote"
)

func (s *Server) listRemotes(w http.ResponseWriter, r *http.Request) {
	if s.remotes == nil {
		writeError(w, http.StatusServiceUnavailable, errors.New("remote control is not configured"))
		return
	}
	items, err := s.remotes.List(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Remote controls listed", items)
}

func (s *Server) getRemote(w http.ResponseWriter, r *http.Request) {
	item, err := s.remotes.Get(r.Context(), r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Remote control loaded", item)
}

func (s *Server) createRemote(w http.ResponseWriter, r *http.Request) {
	var in remote.CreateInput
	if !decode(w, r, &in) {
		return
	}
	item, err := s.remotes.Create(r.Context(), in)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Remote control created", item)
}

func (s *Server) updateRemote(w http.ResponseWriter, r *http.Request) {
	var in remote.UpdateInput
	if !decode(w, r, &in) {
		return
	}
	item, err := s.remotes.Update(r.Context(), r.PathValue("id"), in)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Remote control updated", item)
}

func (s *Server) deleteRemote(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.remotes.Delete(r.Context(), id); errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	} else if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Remote control deleted", map[string]string{"id": id})
}

func (s *Server) pairRemote(w http.ResponseWriter, r *http.Request) {
	item, err := s.remotes.Pair(r.Context(), r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Remote control paired", item)
}

func (s *Server) sendRemoteKey(w http.ResponseWriter, r *http.Request) {
	var in remote.KeyInput
	if !decode(w, r, &in) {
		return
	}
	item, err := s.remotes.Send(r.Context(), r.PathValue("id"), in.Key)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Remote command sent", item)
}
