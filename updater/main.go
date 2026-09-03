package main

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

var releasePattern = regexp.MustCompile(`^v[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?$`)

type updater struct {
	mu      sync.Mutex
	running bool
	token   string
}

func main() {
	u := &updater{token: strings.TrimSpace(os.Getenv("VALKYRIS_UPDATER_TOKEN"))}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", u.health)
	mux.HandleFunc("POST /v1/update", u.update)
	server := &http.Server{
		Addr: ":8080", Handler: mux, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second,
	}
	slog.Info("Valkyris updater started")
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		slog.Error("updater server stopped", "error", err)
		os.Exit(1)
	}
}

func (u *updater) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, "Updater is healthy", map[string]string{"status": "ok"})
}

func (u *updater) update(w http.ResponseWriter, r *http.Request) {
	if !u.authorized(r) {
		writeError(w, http.StatusUnauthorized, "authentication required")
		return
	}
	var input struct {
		Version string `json:"version"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil || !releasePattern.MatchString(input.Version) {
		writeError(w, http.StatusBadRequest, "a valid release version is required")
		return
	}
	u.mu.Lock()
	if u.running {
		u.mu.Unlock()
		writeError(w, http.StatusConflict, "an update is already running")
		return
	}
	u.running = true
	u.mu.Unlock()
	writeJSON(w, http.StatusAccepted, "Backend update started", map[string]string{"status": "started", "version": input.Version})
	go u.run(input.Version)
}

func (u *updater) authorized(r *http.Request) bool {
	if u.token == "" {
		return false
	}
	provided := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	return len(provided) == len(u.token) && subtle.ConstantTimeCompare([]byte(provided), []byte(u.token)) == 1
}

func (u *updater) run(version string) {
	defer func() {
		u.mu.Lock()
		u.running = false
		u.mu.Unlock()
	}()
	installDir := env("VALKYRIS_INSTALL_DIR", "/workspace")
	composeFile := filepath.Join(installDir, "compose.yaml")
	envFile := filepath.Join(installDir, ".env")
	previousVersion := readEnvValue(envFile, "VALKYRIS_VERSION")
	if err := runCompose(installDir, composeFile, envFile, version, "pull", "valkyris"); err != nil {
		slog.Error("pull backend update", "error", err)
		return
	}
	if err := setEnvValue(envFile, "VALKYRIS_VERSION", version); err != nil {
		slog.Error("save backend version", "error", err)
		return
	}
	if err := runCompose(installDir, composeFile, envFile, version, "up", "-d", "--no-build", "--remove-orphans", "valkyris", "mediamtx"); err != nil {
		slog.Error("activate backend update", "error", err)
		if previousVersion != "" && releasePattern.MatchString(previousVersion) {
			restoreErr := setEnvValue(envFile, "VALKYRIS_VERSION", previousVersion)
			if restoreErr == nil {
				restoreErr = runCompose(installDir, composeFile, envFile, previousVersion, "up", "-d", "--no-build", "--remove-orphans", "valkyris", "mediamtx")
			}
			if restoreErr != nil {
				slog.Error("restore previous backend version", "error", restoreErr)
			}
		}
		return
	}
	slog.Info("Valkyris backend update completed", "version", version)
}

func readEnvValue(path, key string) string {
	content, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(content), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, key+"=") {
			return strings.TrimSpace(strings.TrimPrefix(trimmed, key+"="))
		}
	}
	return ""
}

func runCompose(installDir, composeFile, envFile, version string, args ...string) error {
	base := []string{"compose", "--project-name", "valkyris", "--project-directory", installDir, "--env-file", envFile, "-f", composeFile}
	command := exec.Command("docker", append(base, args...)...)
	command.Env = append(os.Environ(), "VALKYRIS_VERSION="+version)
	output, err := command.CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker compose %s: %w: %s", args[0], err, strings.TrimSpace(string(output)))
	}
	return nil
}

func setEnvValue(path, key, value string) error {
	content, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	lines := strings.Split(string(content), "\n")
	found := false
	for index, line := range lines {
		if strings.HasPrefix(strings.TrimSpace(line), key+"=") {
			lines[index] = key + "=" + value
			found = true
		}
	}
	if !found {
		lines = append(lines, key+"="+value)
	}
	updated := strings.TrimSpace(strings.Join(lines, "\n")) + "\n"
	temporary, err := os.CreateTemp(filepath.Dir(path), ".env.update-*")
	if err != nil {
		return err
	}
	name := temporary.Name()
	defer os.Remove(name)
	if err = temporary.Chmod(0o600); err == nil {
		_, err = temporary.WriteString(updated)
	}
	if closeErr := temporary.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(name, path)
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func writeJSON(w http.ResponseWriter, status int, message string, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "message": message, "data": data})
}

func writeError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "message": message, "error": message})
}
