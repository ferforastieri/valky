package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
)

// localizedResponses owns the text returned by the HTTP API. The mobile client
// sends Accept-Language for every request; no user-facing API outcome is
// translated or replaced by the client.
func localizedResponses(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/realtime") || strings.Contains(r.URL.Path, "/live/") ||
			strings.HasSuffix(r.URL.Path, "/snapshot") || strings.HasSuffix(r.URL.Path, "/recording") || strings.HasSuffix(r.URL.Path, "/clip") {
			next.ServeHTTP(w, r)
			return
		}
		buffer := &localizedWriter{header: make(http.Header), status: http.StatusOK}
		next.ServeHTTP(buffer, r)
		buffer.writeTo(w, requestLanguage(r))
	})
}

type localizedWriter struct {
	header      http.Header
	status      int
	wroteHeader bool
	body        bytes.Buffer
}

func (w *localizedWriter) Header() http.Header { return w.header }
func (w *localizedWriter) WriteHeader(status int) {
	if w.wroteHeader {
		return
	}
	w.status = status
	w.wroteHeader = true
}
func (w *localizedWriter) Write(body []byte) (int, error) {
	if !w.wroteHeader {
		w.WriteHeader(http.StatusOK)
	}
	return w.body.Write(body)
}

func (w *localizedWriter) writeTo(dst http.ResponseWriter, language string) {
	body := w.body.Bytes()
	var envelope map[string]any
	if json.Unmarshal(body, &envelope) == nil {
		success, _ := envelope["success"].(bool)
		if message, ok := envelope["message"].(string); ok {
			envelope["message"] = localizedMessage(message, success, language)
		}
		if message, ok := envelope["error"].(string); ok {
			envelope["error"] = localizedMessage(message, false, language)
		}
		if encoded, err := json.Marshal(envelope); err == nil {
			body = encoded
		}
	}
	for key, values := range w.header {
		dst.Header().Del(key)
		for _, value := range values {
			if key == messageHeader {
				value = localizedMessage(value, w.status < http.StatusBadRequest, language)
			}
			dst.Header().Add(key, value)
		}
	}
	dst.Header().Del("Content-Length")
	dst.WriteHeader(w.status)
	_, _ = dst.Write(body)
}

func requestLanguage(r *http.Request) string {
	if strings.HasPrefix(strings.ToLower(r.Header.Get("Accept-Language")), "en") {
		return "en"
	}
	return "pt-BR"
}

func localizedMessage(message string, success bool, language string) string {
	if language == "en" {
		return message
	}
	translations := map[string]string{
		"Valkyris is healthy":                     "Valkyris está saudável",
		"Place created successfully":              "Área criada com sucesso",
		"Place updated successfully":              "Área atualizada com sucesso",
		"Place removed successfully":              "Área removida com sucesso",
		"Places loaded successfully":              "Áreas carregadas com sucesso",
		"place name is required":                  "O nome da área é obrigatório",
		"place coordinates or radius are invalid": "As coordenadas ou o raio da área são inválidos",
		"authentication required":                 "Autenticação obrigatória",
		"administrator access required":           "Acesso de administrador obrigatório",
		"invalid credentials":                     "Credenciais inválidas",
		"password is required":                    "A senha é obrigatória",
		"device name is required":                 "O nome do dispositivo é obrigatório",
		"Request completed successfully":          "Solicitação concluída com sucesso",
		"Resource created successfully":           "Recurso criado com sucesso",
		"Person created successfully":             "Pessoa criada com sucesso",
		"Person updated successfully":             "Pessoa atualizada com sucesso",
		"Person removed successfully":             "Pessoa removida com sucesso",
		"Family user updated successfully":        "Usuário da família atualizado com sucesso",
		"Home password changed successfully":      "Senha da casa alterada com sucesso",
		"Camera created successfully":             "Câmera criada com sucesso",
		"Camera updated successfully":             "Câmera atualizada com sucesso",
		"Camera removed successfully":             "Câmera removida com sucesso",
		"Rule created successfully":               "Regra criada com sucesso",
		"Rule updated successfully":               "Regra atualizada com sucesso",
		"Rule removed successfully":               "Regra removida com sucesso",
		"Event acknowledged successfully":         "Notificação marcada como lida",
		"All events acknowledged successfully":    "Todas as notificações foram marcadas como lidas",
		"Push configuration saved successfully":   "Configuração de notificações salva com sucesso",
		"Retention settings saved successfully":   "Configuração de retenção salva com sucesso",
		"Update started successfully":             "Atualização iniciada com sucesso",
	}
	if translated, ok := translations[message]; ok {
		return translated
	}
	if strings.HasPrefix(message, "invalid request:") {
		return "A solicitação é inválida"
	}
	if success {
		return "Operação concluída com sucesso"
	}
	return "Não foi possível concluir a operação"
}
