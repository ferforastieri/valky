package api

import (
	"database/sql"
	"errors"
	"net/http"
	"strconv"
)

type favoriteInput struct {
	Favorite bool `json:"favorite"`
}

// setFavorite pins or unpins a camera, light or remote control on the overview.
func (s *Server) setFavorite(w http.ResponseWriter, r *http.Request) {
	var in favoriteInput
	if !decode(w, r, &in) {
		return
	}
	id := r.PathValue("id")
	var err error
	switch r.PathValue("type") {
	case "camera":
		err = s.cameras.SetFavorite(r.Context(), id, in.Favorite)
	case "light":
		if s.lights == nil {
			writeError(w, http.StatusServiceUnavailable, errors.New("lighting is not configured"))
			return
		}
		err = s.lights.SetFavorite(r.Context(), id, in.Favorite)
	case "remote":
		if s.remotes == nil {
			writeError(w, http.StatusServiceUnavailable, errors.New("remote control is not configured"))
			return
		}
		_, err = s.remotes.SetFavorite(r.Context(), id, in.Favorite)
	default:
		writeError(w, http.StatusBadRequest, errors.New("unknown favorite type"))
		return
	}
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Favorite updated", map[string]any{"type": r.PathValue("type"), "id": id, "favorite": in.Favorite})
}

// cameraStats returns aggregated event statistics for one camera.
func (s *Server) cameraStats(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if _, _, err := s.cameras.Get(r.Context(), id); errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, err)
		return
	} else if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	days := 30
	if value, err := strconv.Atoi(r.URL.Query().Get("days")); err == nil && value > 0 && value <= 365 {
		days = value
	}
	stats, err := s.events.CameraStats(r.Context(), id, days)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Camera statistics loaded", stats)
}
