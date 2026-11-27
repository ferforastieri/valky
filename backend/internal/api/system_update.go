package api

import (
	"errors"
	"net/http"

	"github.com/ferforastieri/valky/backend/internal/updater"
)

// startSystemUpdate asks the host-side updater to run the official installer.
// The backend never touches Docker: it only sends the authenticated request
// over the local Unix socket provisioned by install.sh.
func (s *Server) startSystemUpdate(w http.ResponseWriter, r *http.Request) {
	if s.updater == nil || !s.updater.Enabled() {
		writeError(w, http.StatusServiceUnavailable, errors.New("automatic updates are not configured"))
		return
	}
	status, err := s.updater.Start(r.Context())
	if errors.Is(err, updater.ErrBusy) {
		writeError(w, http.StatusConflict, err)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	writeSuccess(w, http.StatusAccepted, "Update started", status)
}
