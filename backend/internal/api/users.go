package api

import (
	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"net/http"
)

func (s *Server) adminUsers(w http.ResponseWriter, r *http.Request) {
	out, err := s.auth.Users(r.Context())
	respondWithMessage(w, out, err, "Users loaded successfully")
}
func (s *Server) adminUpdateUser(w http.ResponseWriter, r *http.Request) {
	var in auth.ManagedUser
	if !decode(w, r, &in) {
		return
	}
	if err := s.auth.ManageUser(r.Context(), r.PathValue("id"), in, false); err != nil {
		writeError(w, 400, err)
		return
	}
	writeSuccess(w, 200, "User updated successfully", in)
}
func (s *Server) adminDeleteUser(w http.ResponseWriter, r *http.Request) {
	if err := s.auth.ManageUser(r.Context(), r.PathValue("id"), auth.ManagedUser{}, true); err != nil {
		writeError(w, 400, err)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) ruleRecipients(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDs []string `json:"recipientUserIds"`
	}
	if !decode(w, r, &in) {
		return
	}
	if err := s.rules.SetRecipients(r.Context(), r.PathValue("id"), in.IDs); err != nil {
		writeError(w, 400, err)
		return
	}
	writeSuccess(w, 200, "Recipients updated successfully", in)
}
