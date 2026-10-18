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
		// Arquivos estáticos não têm envelope para traduzir: bufferizar o painel
		// inteiro (centenas de KB) só para falhar no parse custa memória e CPU.
		if strings.HasPrefix(r.URL.Path, "/app/") || r.URL.Path == "/openapi.yaml" {
			next.ServeHTTP(w, r)
			return
		}
		// O cliente que pede inglês recebe exatamente o que o servidor escreveu.
		if requestLanguage(r) == "en" {
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
		changed := false
		if message, ok := envelope["message"].(string); ok {
			if translated := localizedMessage(message, success, language); translated != message {
				envelope["message"] = translated
				changed = true
			}
		}
		if message, ok := envelope["error"].(string); ok {
			if translated := localizedMessage(message, false, language); translated != message {
				envelope["error"] = translated
				changed = true
			}
		}
		// Re-serializar só quando algo mudou: sem tradução, a resposta original
		// (já válida) segue direto para o cliente.
		if changed {
			if encoded, err := json.Marshal(envelope); err == nil {
				body = encoded
			}
		}
	}
	if !bytes.Equal(body, w.body.Bytes()) {
		dst.Header().Del("Content-Length")
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
	dst.WriteHeader(w.status)
	_, _ = dst.Write(body)
}

func requestLanguage(r *http.Request) string {
	if strings.HasPrefix(strings.ToLower(r.Header.Get("Accept-Language")), "en") {
		return "en"
	}
	return "pt-BR"
}

// localizedTranslations é fixo: construir o mapa a cada resposta alocava ~80
// entradas por requisição.
var localizedTranslations = map[string]string{
	"use 3 to 40 letters, digits, dots, underscores or hyphens for username": "Use de 3 a 40 letras sem acento, números, ponto, hífen ou sublinhado no usuário.",
	"password must contain at least 12 characters and at most 72 bytes":      "Use pelo menos 12 caracteres na senha. Se ela for muito longa, reduza o tamanho.",
	"username unavailable": "Este usuário já está em uso.",
	"username unavailable; sign in if you already have an account": "Este usuário já está em uso. Se a conta é sua, entre com seu usuário e senha.",
	"current password is invalid":                                  "A senha atual está incorreta.",
	"Account credentials updated successfully":                     "Credenciais da conta atualizadas com sucesso.",
	"keep at least one active administrator":                       "Mantenha pelo menos um administrador ativo.",

	"Valky is healthy":                            "Valky está saudável",
	"Place created successfully":                  "Área criada com sucesso",
	"Place updated successfully":                  "Área atualizada com sucesso",
	"Place removed successfully":                  "Área removida com sucesso",
	"Places loaded successfully":                  "Áreas carregadas com sucesso",
	"place name is required":                      "O nome da área é obrigatório",
	"place coordinates or radius are invalid":     "As coordenadas ou o raio da área são inválidos",
	"authentication required":                     "Autenticação obrigatória",
	"administrator access required":               "Acesso de administrador obrigatório",
	"invalid credentials":                         "Credenciais inválidas",
	"password is required":                        "A senha é obrigatória",
	"device name is required":                     "O nome do dispositivo é obrigatório",
	"Request completed successfully":              "Solicitação concluída com sucesso",
	"Resource created successfully":               "Recurso criado com sucesso",
	"Person created successfully":                 "Pessoa criada com sucesso",
	"Person updated successfully":                 "Pessoa atualizada com sucesso",
	"Person removed successfully":                 "Pessoa removida com sucesso",
	"Family user updated successfully":            "Usuário da família atualizado com sucesso",
	"Home password changed successfully":          "Senha da casa alterada com sucesso",
	"Camera created successfully":                 "Câmera criada com sucesso",
	"Camera updated successfully":                 "Câmera atualizada com sucesso",
	"Camera removed successfully":                 "Câmera removida com sucesso",
	"Rule created successfully":                   "Regra criada com sucesso",
	"Rule updated successfully":                   "Regra atualizada com sucesso",
	"Rule removed successfully":                   "Regra removida com sucesso",
	"Event acknowledged successfully":             "Notificação marcada como lida",
	"All events acknowledged successfully":        "Todas as notificações foram marcadas como lidas",
	"Events acknowledged successfully":            "Todas as notificações foram marcadas como lidas",
	"Push configuration saved successfully":       "Configuração de notificações salva com sucesso",
	"Retention settings saved successfully":       "Configuração de retenção salva com sucesso",
	"Media retention settings saved successfully": "Configuração de retenção salva com sucesso",
	"Update started successfully":                 "Atualização iniciada com sucesso",

	"Remote control created":                    "Controle remoto criado",
	"Remote control updated":                    "Controle remoto atualizado",
	"Remote control deleted":                    "Controle remoto removido",
	"Remote control paired":                     "Controle remoto pareado",
	"Remote command sent":                       "Comando enviado para a TV",
	"Remote apps listed":                        "Aplicativos da TV carregados",
	"Application launched":                      "Aplicativo aberto na TV",
	"Wake-on-LAN sent":                          "Ligando a TV",
	"TV powered on":                             "Ligando a TV",
	"TV powered off":                            "Desligando a TV",
	"Light controlled":                          "Luz atualizada",
	"Light deleted":                             "Luz removida",
	"User updated successfully":                 "Usuário atualizado com sucesso",
	"Person tracking created":                   "Pessoa adicionada com sucesso",
	"Administrator created successfully":        "Administrador criado com sucesso",
	"Device paired successfully":                "Dispositivo pareado com sucesso",
	"Login completed successfully":              "Login realizado com sucesso",
	"Location recorded successfully":            "Localização registrada",
	"Favorite updated":                          "Favorito atualizado",
	"Firebase service account saved":            "Conta de serviço do Firebase salva",
	"Push device registered successfully":       "Notificações ativadas neste aparelho",
	"Recipients updated successfully":           "Destinatários atualizados",
	"Temporary invitation created successfully": "Convite temporário criado",
	"Startup position updated":                  "Posição inicial atualizada",
	"PTZ command accepted":                      "Comando da câmera aceito",
	"PTZ preset saved":                          "Posição da câmera salva",
	"PTZ preset applied":                        "Posição da câmera aplicada",
	"PTZ preset removed":                        "Posição da câmera removida",
}

func localizedMessage(message string, success bool, language string) string {
	if language == "en" {
		return message
	}
	if translated, ok := localizedTranslations[message]; ok {
		return translated
	}
	if strings.HasPrefix(message, "invalid request:") {
		return "A solicitação é inválida"
	}
	// Never replace the server outcome with a canned sentence: when there is no
	// translation, the backend's own message is the truthful one to show.
	return message
}
