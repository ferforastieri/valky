// Package tuyaimport imports lamps that are already paired to the Tuya app.
// The user authorizes a terminal by scanning a QR code; the Tuya cloud returns
// the device list with each local key, and the existing local registration
// flow validates the key against the lamp before storing anything.
package tuyaimport

import (
	"context"
	"errors"
	"net"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/ferforastieri/valky/backend/internal/light"
	"github.com/ferforastieri/valky/backend/internal/light/tuya"
	"github.com/google/uuid"
)

const (
	// SessionTTL is how long a QR can wait for the phone. The Tuya token itself
	// expires sooner; this only bounds the in-memory session.
	SessionTTL  = 10 * time.Minute
	maxSessions = 32
)

var (
	ErrSessionNotFound = errors.New("sessão de importação não encontrada")
	ErrNotAuthorized   = errors.New("a autorização da Tuya ainda não foi concluída")
	ErrNoDevices       = errors.New("selecione pelo menos uma lâmpada")
)

type Cloud interface {
	StartQR(ctx context.Context, userCode string) (string, error)
	PollQR(ctx context.Context, qrToken, userCode string) (*tuya.CloudLogin, bool, error)
	ListDevices(ctx context.Context, login *tuya.CloudLogin) ([]tuya.CloudDevice, error)
}

type Registrar interface {
	Register(ctx context.Context, in light.RegisterInput) (light.Light, error)
}

type Manager struct {
	cloud     Cloud
	registrar Registrar
	now       func() time.Time

	mu       sync.Mutex
	sessions map[string]*session
}

type session struct {
	mu        sync.Mutex
	id        string
	userCode  string
	qrToken   string
	createdAt time.Time
	expiresAt time.Time
	login     *tuya.CloudLogin
	devices   []tuya.CloudDevice
	err       string
	applying  bool
}

type StartResult struct {
	SessionID string    `json:"sessionId"`
	QRContent string    `json:"qrContent"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// Device is the sanitized device view returned to the app. The local key never
// leaves the backend: the app selects device IDs and the server imports them.
type Device struct {
	DeviceID    string `json:"deviceId"`
	Name        string `json:"name"`
	IP          string `json:"ip,omitempty"`
	Online      bool   `json:"online"`
	Category    string `json:"category,omitempty"`
	ProductID   string `json:"productId,omitempty"`
	ProductName string `json:"productName,omitempty"`
	Sub         bool   `json:"sub,omitempty"`
	HasLocalKey bool   `json:"hasLocalKey"`
}

type Status struct {
	Status    string    `json:"status"`
	Devices   []Device  `json:"devices,omitempty"`
	Error     string    `json:"error,omitempty"`
	ExpiresAt time.Time `json:"expiresAt,omitempty"`
}

type Imported struct {
	DeviceID string      `json:"deviceId"`
	Light    light.Light `json:"light"`
}

type Failed struct {
	DeviceID string `json:"deviceId"`
	Error    string `json:"error"`
}

type ApplyResult struct {
	Imported []Imported `json:"imported"`
	Failed   []Failed   `json:"failed"`
}

func NewManager(registrar Registrar) *Manager {
	return NewManagerWithCloud(registrar, tuya.NewCloudClient())
}

func NewManagerWithCloud(registrar Registrar, cloud Cloud) *Manager {
	return &Manager{
		cloud:     cloud,
		registrar: registrar,
		now:       time.Now,
		sessions:  map[string]*session{},
	}
}

func (m *Manager) Start(ctx context.Context, userCode string) (StartResult, error) {
	userCode = strings.TrimSpace(userCode)
	if userCode == "" {
		return StartResult{}, errors.New("informe o User Code do aplicativo Tuya")
	}
	token, err := m.cloud.StartQR(ctx, userCode)
	if err != nil {
		return StartResult{}, err
	}
	now := m.now()
	item := &session{
		id:        uuid.NewString(),
		userCode:  userCode,
		qrToken:   token,
		createdAt: now,
		expiresAt: now.Add(SessionTTL),
	}
	m.mu.Lock()
	m.cleanupLocked(now)
	m.sessions[item.id] = item
	m.evictLocked()
	m.mu.Unlock()
	return StartResult{
		SessionID: item.id,
		QRContent: tuya.QRContentPrefix + token,
		ExpiresAt: item.expiresAt,
	}, nil
}

// Poll checks the Tuya authorization and, once authorized, loads the device
// list. It is safe to call repeatedly while the sheet is open.
func (m *Manager) Poll(ctx context.Context, id string) (Status, error) {
	item := m.session(id)
	if item == nil {
		return Status{}, ErrSessionNotFound
	}
	item.mu.Lock()
	defer item.mu.Unlock()
	if m.now().After(item.expiresAt) {
		return Status{Status: "expired", ExpiresAt: item.expiresAt}, nil
	}
	if item.err != "" {
		return Status{Status: "error", Error: item.err, ExpiresAt: item.expiresAt}, nil
	}
	if item.login == nil {
		login, authorized, err := m.cloud.PollQR(ctx, item.qrToken, item.userCode)
		if err != nil {
			item.err = err.Error()
			return Status{Status: "error", Error: item.err, ExpiresAt: item.expiresAt}, nil
		}
		if !authorized {
			return Status{Status: "pending", ExpiresAt: item.expiresAt}, nil
		}
		item.login = login
	}
	if item.devices == nil {
		devices, err := m.cloud.ListDevices(ctx, item.login)
		if err != nil {
			item.err = err.Error()
			return Status{Status: "error", Error: item.err, ExpiresAt: item.expiresAt}, nil
		}
		item.devices = devices
	}
	return statusOf(item), nil
}

// Apply registers the selected devices. A device that is offline, is a Zigbee
// sub-device or has no local key is reported individually so the app can show
// exactly which lamps need attention.
func (m *Manager) Apply(ctx context.Context, id string, deviceIDs []string) (ApplyResult, error) {
	item := m.session(id)
	if item == nil {
		return ApplyResult{}, ErrSessionNotFound
	}
	selected := map[string]bool{}
	for _, deviceID := range deviceIDs {
		if deviceID = strings.TrimSpace(deviceID); deviceID != "" {
			selected[deviceID] = true
		}
	}
	if len(selected) == 0 {
		return ApplyResult{}, ErrNoDevices
	}
	item.mu.Lock()
	if m.now().After(item.expiresAt) {
		item.mu.Unlock()
		return ApplyResult{}, ErrSessionNotFound
	}
	if item.login == nil || item.devices == nil {
		item.mu.Unlock()
		return ApplyResult{}, ErrNotAuthorized
	}
	if item.applying {
		item.mu.Unlock()
		return ApplyResult{}, errors.New("a importação já está em andamento")
	}
	item.applying = true
	devices := append([]tuya.CloudDevice(nil), item.devices...)
	item.mu.Unlock()
	defer func() {
		item.mu.Lock()
		item.applying = false
		item.mu.Unlock()
	}()

	byID := make(map[string]tuya.CloudDevice, len(devices))
	for _, device := range devices {
		byID[device.ID] = device
	}
	result := ApplyResult{Imported: []Imported{}, Failed: []Failed{}}
	for deviceID := range selected {
		device, ok := byID[deviceID]
		if !ok {
			result.Failed = append(result.Failed, Failed{DeviceID: deviceID, Error: "dispositivo não está mais na conta da Tuya"})
			continue
		}
		if reason := importBlocker(device); reason != "" {
			result.Failed = append(result.Failed, Failed{DeviceID: deviceID, Error: reason})
			continue
		}
		created, err := m.registrar.Register(ctx, light.RegisterInput{
			Name:      device.Name,
			Address:   strings.TrimSpace(device.IP),
			DeviceID:  device.ID,
			LocalKey:  device.LocalKey,
			ProductID: device.ProductID,
		})
		if err != nil {
			result.Failed = append(result.Failed, Failed{DeviceID: deviceID, Error: err.Error()})
			continue
		}
		result.Imported = append(result.Imported, Imported{DeviceID: deviceID, Light: created})
	}
	sort.Slice(result.Imported, func(i, j int) bool { return result.Imported[i].DeviceID < result.Imported[j].DeviceID })
	sort.Slice(result.Failed, func(i, j int) bool { return result.Failed[i].DeviceID < result.Failed[j].DeviceID })
	return result, nil
}

func (m *Manager) Cancel(id string) {
	m.mu.Lock()
	delete(m.sessions, id)
	m.mu.Unlock()
}

func (m *Manager) session(id string) *session {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.cleanupLocked(m.now())
	return m.sessions[id]
}

func (m *Manager) cleanupLocked(now time.Time) {
	for id, item := range m.sessions {
		if now.After(item.expiresAt.Add(5 * time.Minute)) {
			delete(m.sessions, id)
		}
	}
}

// evictLocked keeps memory bounded if somebody starts many QR sessions. The
// oldest sessions go first; the current one is always the newest.
func (m *Manager) evictLocked() {
	for len(m.sessions) > maxSessions {
		var oldest *session
		for _, item := range m.sessions {
			if oldest == nil || item.createdAt.Before(oldest.createdAt) {
				oldest = item
			}
		}
		if oldest == nil {
			return
		}
		delete(m.sessions, oldest.id)
	}
}

func statusOf(item *session) Status {
	status := Status{Devices: []Device{}, ExpiresAt: item.expiresAt}
	if item.login == nil {
		status.Status = "pending"
		return status
	}
	status.Status = "authorized"
	for _, device := range item.devices {
		status.Devices = append(status.Devices, Device{
			DeviceID:    device.ID,
			Name:        device.Name,
			IP:          device.IP,
			Online:      device.Online,
			Category:    device.Category,
			ProductID:   device.ProductID,
			ProductName: device.ProductName,
			Sub:         device.Sub,
			HasLocalKey: len(strings.TrimSpace(device.LocalKey)) == 16,
		})
	}
	return status
}

func importBlocker(device tuya.CloudDevice) string {
	if device.Sub {
		return "é um sub-dispositivo Zigbee; o Valky só controla lâmpadas Wi-Fi localmente"
	}
	if len(strings.TrimSpace(device.LocalKey)) != 16 {
		return "a Tuya não devolveu chave local para esta lâmpada (provavelmente Zigbee)"
	}
	ip := net.ParseIP(strings.TrimSpace(device.IP)).To4()
	if ip == nil || !ip.IsPrivate() {
		return "a lâmpada está sem IP local; ligue-a no Wi-Fi e tente de novo"
	}
	if !device.Online {
		return "a lâmpada está offline no aplicativo da Tuya"
	}
	return ""
}

// Compile-time check that the local light service is a valid registrar.
var _ Registrar = (*light.Service)(nil)
