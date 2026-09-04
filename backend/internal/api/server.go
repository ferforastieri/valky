package api

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/camera"
	"github.com/ferforastieri/valkyris/backend/internal/detector"
	"github.com/ferforastieri/valkyris/backend/internal/event"
	"github.com/ferforastieri/valkyris/backend/internal/media"
	"github.com/ferforastieri/valkyris/backend/internal/notify"
	"github.com/ferforastieri/valkyris/backend/internal/preferences"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
	"github.com/ferforastieri/valkyris/backend/internal/updates"
)

//go:embed openapi.yaml
var openAPI []byte

type DetectionSubmitter interface {
	Submit(context.Context, rules.Detection) ([]event.Event, error)
}
type Server struct {
	auth         *auth.Manager
	cameras      *camera.Repository
	onvif        *camera.ONVIFClient
	media        *media.Manager
	rules        *rules.Service
	events       *event.Service
	notify       *notify.Service
	hub          *Hub
	submitter    DetectionSubmitter
	logger       *slog.Logger
	operationsMu sync.RWMutex
	operations   map[string]CameraOperation
	updates      *updates.Service
	preferences  *preferences.Service
}

type CameraOperation struct {
	ID        string         `json:"id"`
	Status    string         `json:"status"`
	Message   string         `json:"message"`
	Camera    *camera.Camera `json:"camera,omitempty"`
	CreatedAt time.Time      `json:"createdAt"`
	UpdatedAt time.Time      `json:"updatedAt"`
}

func NewServer(a *auth.Manager, c *camera.Repository, o *camera.ONVIFClient, m *media.Manager, r *rules.Service, e *event.Service, n *notify.Service, h *Hub, logger *slog.Logger) *Server {
	return &Server{auth: a, cameras: c, onvif: o, media: m, rules: r, events: e, notify: n, hub: h, logger: logger, operations: make(map[string]CameraOperation)}
}
func (s *Server) SetSubmitter(sub DetectionSubmitter)         { s.submitter = sub }
func (s *Server) SetUpdates(service *updates.Service)         { s.updates = service }
func (s *Server) SetPreferences(service *preferences.Service) { s.preferences = service }

// ResumeCameraSetups continues cameras that were persisted before an interrupted
// background probe. Failed cameras remain untouched so their diagnosis is kept.
func (s *Server) ResumeCameraSetups(ctx context.Context) {
	cameras, err := s.cameras.List(ctx)
	if err != nil {
		s.logger.Warn("list camera setups to resume", "error", err)
		return
	}
	for _, cam := range cameras {
		if cam.SetupStatus != "pending" {
			continue
		}
		_, credentials, getErr := s.cameras.Get(ctx, cam.ID)
		if getErr != nil {
			s.failCameraOperation(cam.ID, camera.CreateInput{}, getErr)
			continue
		}
		operation := operationFromCamera(cam)
		s.operationsMu.Lock()
		s.operations[cam.ID] = operation
		s.operationsMu.Unlock()
		input := camera.CreateInput{Name: cam.Name, Host: cam.Host, Port: cam.Port, Username: credentials.Username, Password: credentials.Password, RTSPURI: credentials.RTSPURI}
		go s.completeCameraCreation(cam.ID, input)
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.health)
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("GET /openapi.yaml", s.openapi)
	mux.HandleFunc("GET /api/v1/auth/status", s.authStatus)
	mux.HandleFunc("POST /api/v1/admin/bootstrap", s.bootstrapAdmin)
	mux.HandleFunc("POST /api/v1/login", s.login)
	mux.HandleFunc("POST /api/v1/pair", s.pair)
	protected := http.NewServeMux()
	protected.Handle("POST /pairing-sessions", s.auth.RequireAdmin(http.HandlerFunc(s.pairingSession)))
	protected.HandleFunc("GET /cameras", s.listCameras)
	protected.HandleFunc("POST /cameras", s.createCamera)
	protected.HandleFunc("GET /camera-operations/{id}", s.cameraOperation)
	protected.HandleFunc("DELETE /cameras/{id}", s.deleteCamera)
	protected.HandleFunc("POST /cameras/{id}/ptz", s.ptz)
	protected.HandleFunc("GET /cameras/{id}/snapshot", s.snapshot)
	protected.HandleFunc("GET /cameras/{id}/live/{asset...}", s.live)
	protected.HandleFunc("GET /detectors", s.detectors)
	protected.HandleFunc("GET /rules", s.listRules)
	protected.HandleFunc("POST /rules", s.createRule)
	protected.HandleFunc("DELETE /rules/{id}", s.deleteRule)
	protected.HandleFunc("GET /events", s.listEvents)
	protected.HandleFunc("GET /events/{id}", s.getEvent)
	protected.HandleFunc("POST /events/{id}/acknowledge", s.ackEvent)
	protected.HandleFunc("GET /events/{id}/snapshot", s.eventSnapshot)
	protected.HandleFunc("GET /events/{id}/clip", s.eventClip)
	protected.HandleFunc("POST /devices/push", s.push)
	protected.HandleFunc("GET /settings/retention", s.getRetention)
	protected.Handle("PUT /settings/retention", s.auth.RequireAdmin(http.HandlerFunc(s.setRetention)))
	protected.HandleFunc("POST /detections", s.submitDetection)
	protected.HandleFunc("GET /system/update", s.systemUpdate)
	protected.Handle("POST /system/update", s.auth.RequireAdmin(http.HandlerFunc(s.startSystemUpdate)))
	protected.Handle("/realtime", s.hub)
	mux.Handle("/api/v1/", http.StripPrefix("/api/v1", s.auth.Middleware(protected)))
	return requestLog(s.logger, securityHeaders(outcomeHeaders(mux)))
}
func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeSuccess(w, http.StatusOK, "Valkyris is healthy", map[string]any{"status": "ok", "service": "valkyris", "time": time.Now().UTC()})
}
func (s *Server) openapi(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/yaml")
	setOutcomeHeaders(w, http.StatusOK, "OpenAPI contract loaded")
	_, _ = w.Write(openAPI)
}
func (s *Server) authStatus(w http.ResponseWriter, r *http.Request) {
	initialized, err := s.auth.AdminInitialized(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Authentication status loaded", map[string]bool{"initialized": initialized})
}
func (s *Server) bootstrapAdmin(w http.ResponseWriter, r *http.Request) {
	initialized, err := s.auth.AdminInitialized(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	if initialized {
		writeError(w, http.StatusConflict, fmt.Errorf("administrator is already configured"))
		return
	}
	var in auth.LoginRequest
	if !decode(w, r, &in) {
		return
	}
	out, err := s.auth.BootstrapAdmin(r.Context(), in)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Administrator created successfully", out)
}
func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var in auth.LoginRequest
	if !decode(w, r, &in) {
		return
	}
	out, err := s.auth.LoginAdmin(r.Context(), in)
	if err != nil {
		writeError(w, http.StatusUnauthorized, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Login completed successfully", out)
}
func (s *Server) pair(w http.ResponseWriter, r *http.Request) {
	var in auth.PairRequest
	if !decode(w, r, &in) {
		return
	}
	out, err := s.auth.Pair(r.Context(), in)
	if err != nil {
		writeError(w, 401, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Device paired successfully", out)
}
func (s *Server) pairingSession(w http.ResponseWriter, r *http.Request) {
	session, err := s.auth.CreatePairing(r.Context())
	if err != nil {
		writeError(w, 500, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Temporary invitation created successfully", session)
}
func (s *Server) listCameras(w http.ResponseWriter, r *http.Request) {
	out, err := s.cameras.List(r.Context())
	respondWithMessage(w, out, err, "Cameras loaded successfully")
}
func (s *Server) createCamera(w http.ResponseWriter, r *http.Request) {
	var in camera.CreateInput
	if !decode(w, r, &in) {
		return
	}
	cam, err := s.cameras.CreatePending(r.Context(), in)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	now := time.Now().UTC()
	operation := CameraOperation{
		ID:        cam.ID,
		Status:    "pending",
		Message:   "Camera saved; ONVIF validation will continue in the background",
		Camera:    &cam,
		CreatedAt: now,
		UpdatedAt: now,
	}
	s.operationsMu.Lock()
	s.operations[operation.ID] = operation
	for id, candidate := range s.operations {
		if now.Sub(candidate.UpdatedAt) > 15*time.Minute {
			delete(s.operations, id)
		}
	}
	s.operationsMu.Unlock()
	go s.completeCameraCreation(operation.ID, in)
	writeSuccess(w, http.StatusAccepted, operation.Message, operation)
}

func (s *Server) cameraOperation(w http.ResponseWriter, r *http.Request) {
	s.operationsMu.RLock()
	operation, ok := s.operations[r.PathValue("id")]
	s.operationsMu.RUnlock()
	if !ok {
		cam, _, err := s.cameras.Get(r.Context(), r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, fmt.Errorf("camera operation not found"))
			return
		}
		operation = operationFromCamera(cam)
	}
	writeSuccess(w, http.StatusOK, operation.Message, operation)
}

func (s *Server) completeCameraCreation(operationID string, in camera.CreateInput) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	s.updateCameraOperation(ctx, operationID, "pending", "probing", "Connecting to the ONVIF service", "")
	caps, profile, services, err := s.onvif.Probe(ctx, in.Host, defaultPort(in.Port), in.Username, in.Password)
	if err != nil {
		s.failCameraOperation(operationID, in, err)
		return
	}
	s.updateCameraOperation(ctx, operationID, "pending", "stream", "ONVIF connected; preparing the video stream", "")
	_, credentials, err := s.cameras.Get(ctx, operationID)
	if err != nil {
		s.failCameraOperation(operationID, in, err)
		return
	}
	if err = s.media.ConfigureCamera(ctx, operationID, credentials.RTSPURI); err != nil {
		s.failCameraOperation(operationID, in, err)
		return
	}
	if err = s.cameras.CompleteSetup(ctx, operationID, caps, profile, services); err != nil {
		s.failCameraOperation(operationID, in, err)
		return
	}
	cam, _, err := s.cameras.Get(ctx, operationID)
	if err != nil {
		s.failCameraOperation(operationID, in, err)
		return
	}
	s.finishCameraOperation(operationID, CameraOperation{Status: "completed", Message: "Camera is ready and its ONVIF capabilities were discovered", Camera: &cam})
}

func (s *Server) updateCameraOperation(ctx context.Context, id, status, step, message, setupError string) {
	if err := s.cameras.UpdateSetup(ctx, id, status, step, setupError); err != nil {
		s.logger.Warn("persist camera setup state", "camera", id, "error", err)
	}
	cam, _, _ := s.cameras.Get(ctx, id)
	s.finishCameraOperation(id, CameraOperation{Status: status, Message: message, Camera: &cam})
}

func (s *Server) failCameraOperation(id string, in camera.CreateInput, cause error) {
	message := "Camera setup failed: " + safeCameraError(cause, in)
	s.updateCameraOperation(context.Background(), id, "failed", "failed", message, message)
}

func safeCameraError(err error, in camera.CreateInput) string {
	message := err.Error()
	for _, secret := range []string{in.Password, in.Username, in.RTSPURI} {
		if secret != "" {
			message = strings.ReplaceAll(message, secret, "[redacted]")
		}
	}
	return message
}

func operationFromCamera(cam camera.Camera) CameraOperation {
	status := cam.SetupStatus
	message := "Camera setup is in progress"
	if status == "ready" {
		status, message = "completed", "Camera is ready"
	}
	if status == "failed" {
		message = cam.SetupError
	}
	return CameraOperation{ID: cam.ID, Status: status, Message: message, Camera: &cam, CreatedAt: cam.CreatedAt, UpdatedAt: cam.SetupUpdatedAt}
}

func (s *Server) finishCameraOperation(id string, result CameraOperation) {
	s.operationsMu.Lock()
	defer s.operationsMu.Unlock()
	operation, ok := s.operations[id]
	if !ok {
		return
	}
	operation.Status = result.Status
	operation.Message = result.Message
	operation.Camera = result.Camera
	operation.UpdatedAt = time.Now().UTC()
	s.operations[id] = operation
	if s.hub != nil {
		s.hub.Broadcast(map[string]any{"type": "camera.setup", "cameraId": id, "status": operation.Status, "message": operation.Message})
	}
}
func (s *Server) deleteCamera(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	err := s.cameras.Delete(r.Context(), id)
	if err == nil {
		_ = s.media.RemoveCamera(r.Context(), id)
		writeSuccess(w, http.StatusOK, "Camera removed successfully", nil)
		return
	}
	respond(w, nil, err)
}
func (s *Server) ptz(w http.ResponseWriter, r *http.Request) {
	cam, cred, err := s.cameras.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	if !cam.Capabilities.PTZ {
		writeError(w, 409, fmt.Errorf("camera does not advertise PTZ"))
		return
	}
	var command camera.PTZCommand
	if !decode(w, r, &command) {
		return
	}
	if err = s.onvif.PTZ(r.Context(), cam, cred, command); err != nil {
		writeError(w, 502, err)
		return
	}
	writeSuccess(w, http.StatusOK, "PTZ command accepted", nil)
}
func (s *Server) snapshot(w http.ResponseWriter, r *http.Request) {
	cam, cred, err := s.cameras.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	uri, err := s.onvif.SnapshotURI(r.Context(), cam, cred)
	if err != nil {
		writeError(w, 502, err)
		return
	}
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodGet, uri, nil)
	req.SetBasicAuth(cred.Username, cred.Password)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		writeError(w, 502, err)
		return
	}
	defer resp.Body.Close()
	w.Header().Set("Content-Type", resp.Header.Get("Content-Type"))
	w.Header().Set("Cache-Control", "no-store")
	message := "Camera snapshot loaded"
	if resp.StatusCode >= http.StatusBadRequest {
		message = "Camera snapshot request failed: " + resp.Status
	}
	setOutcomeHeaders(w, resp.StatusCode, message)
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 12<<20))
}
func (s *Server) live(w http.ResponseWriter, r *http.Request) {
	target, _ := url.Parse(s.media.HLSBase())
	proxy := httputil.NewSingleHostReverseProxy(target)
	id := r.PathValue("id")
	asset := r.PathValue("asset")
	if asset == "" {
		asset = "index.m3u8"
	}
	proxy.Director = func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.URL.Path = "/camera-" + id + "/" + asset
		req.Host = target.Host
		req.Header.Del("Authorization")
	}
	proxy.ModifyResponse = func(response *http.Response) error {
		response.Header.Set(messageHeader, "Authenticated live stream asset loaded")
		response.Header.Set(successHeader, strconv.FormatBool(response.StatusCode < http.StatusBadRequest))
		return nil
	}
	proxy.ErrorHandler = func(writer http.ResponseWriter, _ *http.Request, err error) {
		writeError(writer, http.StatusBadGateway, fmt.Errorf("live stream unavailable: %w", err))
	}
	proxy.ServeHTTP(w, r)
}
func (s *Server) detectors(w http.ResponseWriter, r *http.Request) {
	writeSuccess(w, http.StatusOK, "Detector catalog loaded successfully", detector.Catalog)
}
func (s *Server) listRules(w http.ResponseWriter, r *http.Request) {
	out, err := s.rules.List(r.Context(), r.URL.Query().Get("cameraId"))
	respondWithMessage(w, out, err, "Rules loaded successfully")
}
func (s *Server) createRule(w http.ResponseWriter, r *http.Request) {
	var in rules.Rule
	if !decode(w, r, &in) {
		return
	}
	out, err := s.rules.Create(r.Context(), in)
	if err != nil {
		writeError(w, 400, err)
		return
	}
	writeSuccess(w, http.StatusCreated, "Rule created successfully", out)
}
func (s *Server) deleteRule(w http.ResponseWriter, r *http.Request) {
	err := s.rules.Delete(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Rule removed successfully", nil)
}
func (s *Server) listEvents(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	out, err := s.events.List(r.Context(), r.URL.Query().Get("cameraId"), limit)
	respondWithMessage(w, out, err, "Events loaded successfully")
}
func (s *Server) getEvent(w http.ResponseWriter, r *http.Request) {
	out, err := s.events.Get(r.Context(), r.PathValue("id"))
	respondWithMessage(w, out, err, "Event loaded successfully")
}
func (s *Server) ackEvent(w http.ResponseWriter, r *http.Request) {
	err := s.events.Acknowledge(r.Context(), r.PathValue("id"), auth.DeviceID(r.Context()))
	if err != nil {
		respond(w, nil, err)
		return
	}
	s.hub.Broadcast(map[string]any{"type": "event.acknowledged", "eventId": r.PathValue("id")})
	writeSuccess(w, http.StatusOK, "Event acknowledged successfully", nil)
}
func (s *Server) eventClip(w http.ResponseWriter, r *http.Request) {
	e, err := s.events.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	if e.ClipPath == "" {
		writeError(w, 404, fmt.Errorf("clip is not ready"))
		return
	}
	if _, err = os.Stat(filepath.Clean(e.ClipPath)); err != nil {
		writeError(w, http.StatusNotFound, fmt.Errorf("event clip file is unavailable: %w", err))
		return
	}
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	setOutcomeHeaders(w, http.StatusOK, "Event clip loaded")
	http.ServeFile(w, r, filepath.Clean(e.ClipPath))
}
func (s *Server) eventSnapshot(w http.ResponseWriter, r *http.Request) {
	e, err := s.events.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	if e.SnapshotPath == "" {
		writeError(w, 404, fmt.Errorf("event snapshot is not ready"))
		return
	}
	if _, err = os.Stat(filepath.Clean(e.SnapshotPath)); err != nil {
		writeError(w, http.StatusNotFound, fmt.Errorf("event snapshot file is unavailable: %w", err))
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=86400")
	setOutcomeHeaders(w, http.StatusOK, "Event snapshot loaded")
	http.ServeFile(w, r, filepath.Clean(e.SnapshotPath))
}
func (s *Server) push(w http.ResponseWriter, r *http.Request) {
	var in notify.Registration
	if !decode(w, r, &in) {
		return
	}
	if err := s.notify.Register(r.Context(), auth.DeviceID(r.Context()), in); err != nil {
		writeError(w, 400, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Push device registered successfully", nil)
}
func (s *Server) getRetention(w http.ResponseWriter, r *http.Request) {
	if s.preferences == nil {
		writeError(w, http.StatusServiceUnavailable, fmt.Errorf("settings service is not configured"))
		return
	}
	value, err := s.preferences.Retention(r.Context())
	respondWithMessage(w, value, err, "Media retention settings loaded successfully")
}
func (s *Server) setRetention(w http.ResponseWriter, r *http.Request) {
	if s.preferences == nil {
		writeError(w, http.StatusServiceUnavailable, fmt.Errorf("settings service is not configured"))
		return
	}
	var value preferences.Retention
	if !decode(w, r, &value) {
		return
	}
	result, err := s.preferences.SetRetention(r.Context(), value)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeSuccess(w, http.StatusOK, "Media retention settings saved successfully", result)
}
func (s *Server) submitDetection(w http.ResponseWriter, r *http.Request) {
	if s.submitter == nil {
		writeError(w, 503, fmt.Errorf("detection pipeline is not ready"))
		return
	}
	var in struct {
		CameraID   string         `json:"cameraId"`
		Type       string         `json:"type"`
		Confidence float64        `json:"confidence"`
		Metadata   map[string]any `json:"metadata"`
	}
	if !decode(w, r, &in) {
		return
	}
	out, err := s.submitter.Submit(r.Context(), rules.Detection{CameraID: in.CameraID, Type: in.Type, Confidence: in.Confidence, OccurredAt: time.Now().UTC(), Metadata: in.Metadata})
	respondWithMessage(w, out, err, "Detection processed successfully")
}

func (s *Server) systemUpdate(w http.ResponseWriter, r *http.Request) {
	if s.updates == nil {
		writeError(w, http.StatusServiceUnavailable, fmt.Errorf("automatic updater is not configured"))
		return
	}
	info, err := s.updates.Check(r.Context(), r.URL.Query().Get("clientVersion"))
	respondWithMessage(w, info, err, info.Message)
}

func (s *Server) startSystemUpdate(w http.ResponseWriter, r *http.Request) {
	if s.updates == nil {
		writeError(w, http.StatusServiceUnavailable, fmt.Errorf("automatic updater is not configured"))
		return
	}
	var in struct {
		ClientVersion string `json:"clientVersion"`
	}
	if !decode(w, r, &in) {
		return
	}
	info, err := s.updates.Start(r.Context(), in.ClientVersion)
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	s.hub.Broadcast(map[string]any{"type": "system.update.started", "version": info.LatestVersion})
	writeSuccess(w, http.StatusAccepted, info.Message, info)
}

func decode(w http.ResponseWriter, r *http.Request, out any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(out); err != nil {
		writeError(w, 400, fmt.Errorf("invalid request: %w", err))
		return false
	}
	return true
}
func respond(w http.ResponseWriter, value any, err error) {
	respondWithMessage(w, value, err, "Request completed successfully")
}
func respondWithMessage(w http.ResponseWriter, value any, err error, message string) {
	if err == nil {
		writeSuccess(w, http.StatusOK, message, value)
		return
	}
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, 404, fmt.Errorf("resource not found"))
		return
	}
	writeError(w, 500, err)
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	writeSuccess(w, status, defaultSuccessMessage(status), value)
}
func writeSuccess(w http.ResponseWriter, status int, message string, value any) {
	w.Header().Set("Content-Type", "application/json")
	setOutcomeHeaders(w, status, message)
	w.WriteHeader(status)
	if value == nil {
		value = map[string]any{}
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "message": message, "data": value})
}
func writeError(w http.ResponseWriter, status int, err error) {
	message := err.Error()
	w.Header().Set("Content-Type", "application/json")
	setOutcomeHeaders(w, status, message)
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "message": message, "error": message})
}

const messageHeader = "X-Valkyris-Message"
const successHeader = "X-Valkyris-Success"

func setOutcomeHeaders(w http.ResponseWriter, status int, message string) {
	w.Header().Set(messageHeader, message)
	w.Header().Set(successHeader, strconv.FormatBool(status < http.StatusBadRequest))
}

func defaultSuccessMessage(status int) string {
	if status == http.StatusCreated {
		return "Resource created successfully"
	}
	return "Request completed successfully"
}

type outcomeWriter struct {
	http.ResponseWriter
}

func (w *outcomeWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *outcomeWriter) WriteHeader(status int) {
	if w.Header().Get(messageHeader) == "" {
		message := http.StatusText(status)
		if message == "" {
			message = "Request completed"
		}
		setOutcomeHeaders(w, status, message)
	}
	w.ResponseWriter.WriteHeader(status)
}
func (w *outcomeWriter) Write(body []byte) (int, error) {
	if w.Header().Get(messageHeader) == "" {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(body)
}

func outcomeHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		next.ServeHTTP(&outcomeWriter{ResponseWriter: w}, r)
	})
}
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}
func requestLog(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		logger.Info("request", "method", r.Method, "path", r.URL.Path, "duration", time.Since(start))
	})
}
func defaultPort(v int) int {
	if v == 0 {
		return 2020
	}
	return v
}
