// Command valky-updater is the host-side updater. It listens on a local Unix
// socket and runs the official installer; the backend never receives Docker
// access. The updater is installed and updated by install.sh.
package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	defaultSocket      = "/run/valky-updater/updater.sock"
	defaultRepository  = "ferforastieri/valky"
	defaultLogPath     = "/var/log/valky-updater.log"
	installerAssetName = "install.sh"
	checksumsAssetName = "SHA256SUMS"
	updateTimeout      = 20 * time.Minute
	maxDownloadSize    = 4 << 20
)

type runner interface {
	Run(ctx context.Context) (string, error)
}

type updateRunner struct {
	repository string
	logPath    string
	home       string
	client     *http.Client
}

func (r updateRunner) Run(ctx context.Context) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, updateTimeout)
	defer cancel()
	base := fmt.Sprintf("https://github.com/%s/releases/latest/download/", r.repository)
	checksums, err := r.download(ctx, base+checksumsAssetName)
	if err != nil {
		return "", err
	}
	installer, err := r.download(ctx, base+installerAssetName)
	if err != nil {
		return "", err
	}
	if err := verifyChecksum(installer, checksums, installerAssetName); err != nil {
		return "", err
	}
	file, err := os.CreateTemp("", "valky-install-*.sh")
	if err != nil {
		return "", fmt.Errorf("criar o instalador temporário: %w", err)
	}
	name := file.Name()
	defer os.Remove(name)
	if _, err := file.Write(installer); err != nil {
		file.Close()
		return "", fmt.Errorf("gravar o instalador temporário: %w", err)
	}
	if err := file.Close(); err != nil {
		return "", err
	}
	if err := os.Chmod(name, 0o700); err != nil {
		return "", err
	}

	var output strings.Builder
	writer := io.Writer(&output)
	var logFile *os.File
	if r.logPath != "" {
		if file, err := os.OpenFile(r.logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
			logFile = file
			defer logFile.Close()
			writer = io.MultiWriter(&output, logFile)
		}
	}
	cmd := exec.CommandContext(ctx, "/bin/sh", name)
	cmd.Env = append(os.Environ(), "VALKY_VERSION=latest")
	if r.home != "" {
		cmd.Dir = r.home
	}
	cmd.Stdout = writer
	cmd.Stderr = writer
	if err := cmd.Run(); err != nil {
		return output.String(), fmt.Errorf("instalador terminou com erro: %w", err)
	}
	return output.String(), nil
}

func (r updateRunner) download(ctx context.Context, url string) ([]byte, error) {
	client := r.client
	if client == nil {
		client = &http.Client{Timeout: 60 * time.Second}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "valky-updater")
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("baixar %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return nil, fmt.Errorf("baixar %s: %s", url, resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxDownloadSize+1))
	if err != nil {
		return nil, err
	}
	if len(body) > maxDownloadSize {
		return nil, fmt.Errorf("baixar %s: arquivo grande demais", url)
	}
	return body, nil
}

func verifyChecksum(content, checksums []byte, asset string) error {
	for _, line := range strings.Split(string(checksums), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		name := strings.TrimPrefix(fields[1], "*")
		if name != asset {
			continue
		}
		sum := sha256.Sum256(content)
		if !strings.EqualFold(fields[0], hex.EncodeToString(sum[:])) {
			return fmt.Errorf("checksum inválido para %s", asset)
		}
		return nil
	}
	return fmt.Errorf("checksum ausente para %s", asset)
}

type status struct {
	State      string    `json:"state"`
	StartedAt  time.Time `json:"startedAt,omitempty"`
	FinishedAt time.Time `json:"finishedAt,omitempty"`
	Error      string    `json:"error,omitempty"`
	Output     string    `json:"output,omitempty"`
}

type server struct {
	token  string
	runner runner

	mu    sync.Mutex
	state status
}

func newServer(token string, runner runner) *server {
	return &server{token: token, runner: runner, state: status{State: "idle"}}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /status", s.auth(s.handleStatus))
	mux.HandleFunc("POST /update", s.auth(s.handleUpdate))
	return mux
}

func (s *server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		provided := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if s.token == "" || subtle.ConstantTimeCompare([]byte(provided), []byte(s.token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "token inválido"})
			return
		}
		next(w, r)
	}
}

func (s *server) handleStatus(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	writeJSON(w, http.StatusOK, s.state)
}

func (s *server) handleUpdate(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	if s.state.State == "running" {
		s.mu.Unlock()
		writeJSON(w, http.StatusConflict, map[string]string{"error": "uma atualização já está em andamento"})
		return
	}
	s.state = status{State: "running", StartedAt: time.Now().UTC()}
	s.mu.Unlock()

	go func() {
		output, err := s.runner.Run(context.Background())
		s.mu.Lock()
		defer s.mu.Unlock()
		s.state.FinishedAt = time.Now().UTC()
		s.state.Output = tail(output, 8<<10)
		if err != nil {
			s.state.State = "failed"
			s.state.Error = err.Error()
		} else {
			s.state.State = "succeeded"
		}
	}()
	writeJSON(w, http.StatusAccepted, map[string]string{"state": "running"})
}

func tail(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return value[len(value)-limit:]
}

func writeJSON(w http.ResponseWriter, code int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(value)
}

func main() {
	socket := strings.TrimSpace(os.Getenv("VALKY_UPDATER_SOCKET"))
	if socket == "" {
		socket = defaultSocket
	}
	token := strings.TrimSpace(os.Getenv("VALKY_UPDATER_TOKEN"))
	if token == "" {
		log.Fatal("VALKY_UPDATER_TOKEN não configurado")
	}
	repository := strings.TrimSpace(os.Getenv("VALKY_UPDATER_REPOSITORY"))
	if repository == "" {
		repository = defaultRepository
	}
	logPath := strings.TrimSpace(os.Getenv("VALKY_UPDATER_LOG"))
	if logPath == "" {
		logPath = defaultLogPath
	}
	updater := updateRunner{repository: repository, logPath: logPath, home: os.Getenv("HOME")}
	srv := newServer(token, updater)

	if err := os.MkdirAll(filepath.Dir(socket), 0o755); err != nil {
		log.Fatalf("criar diretório do socket: %v", err)
	}
	if err := os.Remove(socket); err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Fatalf("remover socket antigo: %v", err)
	}
	listener, err := net.Listen("unix", socket)
	if err != nil {
		log.Fatalf("escutar em %s: %v", socket, err)
	}
	if err := os.Chmod(socket, 0o666); err != nil {
		log.Fatalf("ajustar permissão do socket: %v", err)
	}
	server := &http.Server{
		Handler:           srv.routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	stopping := make(chan os.Signal, 1)
	signal.Notify(stopping, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stopping
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()
	log.Printf("valky-updater escutando em %s", socket)
	if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
