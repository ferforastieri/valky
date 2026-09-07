package api

import (
	"fmt"
	"net/http"
	"strconv"
	"time"
)

func (s *Server) eventActivity(w http.ResponseWriter, r *http.Request) {
	hours, err := strconv.Atoi(r.URL.Query().Get("hours"))
	if err != nil || hours < 12 || hours > 48 || hours%12 != 0 {
		writeError(w, http.StatusBadRequest, fmt.Errorf("hours must be 12, 24, 36 or 48"))
		return
	}
	out, err := s.events.Activity(r.Context(), hours, time.Now())
	respondWithMessage(w, out, err, "Activity loaded successfully")
}

func (s *Server) eventInterval(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	from, fromErr := time.Parse(time.RFC3339Nano, q.Get("from"))
	to, toErr := time.Parse(time.RFC3339Nano, q.Get("to"))
	offset := 0
	var offsetErr error
	if q.Has("offset") {
		offset, offsetErr = strconv.Atoi(q.Get("offset"))
	}
	if fromErr != nil || toErr != nil || !to.After(from) || to.Sub(from) > 48*time.Hour || offsetErr != nil || offset < 0 {
		writeError(w, http.StatusBadRequest, fmt.Errorf("invalid event interval or offset"))
		return
	}
	out, err := s.events.ListInterval(r.Context(), from, to, offset)
	respondWithMessage(w, out, err, "Events loaded successfully")
}
