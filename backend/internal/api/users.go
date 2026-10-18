package api

import (
	"github.com/ferforastieri/valky/backend/internal/auth"
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
	// Promover alguém a administrador por uma sessão de consulta exige a senha
	// de quem está operando: sem isso, o cookie do painel viraria administrador
	// permanente por um dispositivo recém-pareado.
	if in.Admin && auth.IsViewerSession(r.Context()) {
		if current, err := s.auth.UserIsAdmin(r.Context(), r.PathValue("id")); err != nil || !current {
			if err := s.auth.ConfirmPassword(r.Context(), auth.AccountID(r.Context()), in.ActorPassword); err != nil {
				writeError(w, http.StatusForbidden, err)
				return
			}
		}
	}
	if err := s.auth.ManageUser(r.Context(), r.PathValue("id"), in, false); err != nil {
		writeError(w, 400, err)
		return
	}
	in.Password = ""
	in.ActorPassword = ""
	writeSuccess(w, 200, "User updated successfully", in)
}
func (s *Server) adminDeleteUser(w http.ResponseWriter, r *http.Request) {
	if err := s.auth.ManageUser(r.Context(), r.PathValue("id"), auth.ManagedUser{}, true); err != nil {
		writeError(w, 400, err)
		return
	}
	w.WriteHeader(204)
}
