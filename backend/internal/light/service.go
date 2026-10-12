package light

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

type Broadcaster interface{ Broadcast(any) }

type Service struct {
	repo   *Repository
	driver Driver
	hub    Broadcaster
	logger *slog.Logger
	mu     sync.RWMutex
	states map[string]State
	locks  sync.Map
}

func NewService(repo *Repository, driver Driver, hub Broadcaster, logger *slog.Logger) *Service {
	return &Service{repo: repo, driver: driver, hub: hub, logger: logger, states: map[string]State{}}
}

// Available reports whether a local adapter is installed.
func (s *Service) Available() bool { return s.driver != nil && s.driver.Available() }

// Scanner reports whether the installed adapter can look for devices on the
// local network.
func (s *Service) Scanner() (Scanner, bool) {
	scanner, ok := s.driver.(Scanner)
	return scanner, ok && s.Available()
}

// Scan looks for devices that can be registered. It never touches the network
// outside the local subnets the host is connected to.
func (s *Service) Scan(ctx context.Context, in DiscoveryInput) ([]Candidate, error) {
	scanner, ok := s.Scanner()
	if !ok {
		return nil, ErrNoLightingAdapter
	}
	duration := time.Duration(in.Duration) * time.Second
	if duration <= 0 || duration > 30*time.Second {
		duration = 8 * time.Second
	}
	return scanner.Scan(ctx, duration, in.Probe)
}

// Create registers a light and immediately validates that the credentials work,
// so a wrong local key is reported while the user is still on the pairing
// screen instead of failing silently later.
func (s *Service) Create(ctx context.Context, in CreateInput) (Light, error) {
	name := strings.TrimSpace(in.Name)
	if name == "" {
		return Light{}, errors.New("informe um nome para a lâmpada")
	}
	deviceID := strings.TrimSpace(in.DeviceID)
	if deviceID == "" {
		return Light{}, errors.New("selecione uma lâmpada encontrada na rede ou informe o identificador do dispositivo")
	}
	key := strings.TrimSpace(in.LocalKey)
	if len(key) != 16 {
		return Light{}, errors.New("a chave local tem 16 caracteres")
	}
	if in.ProtocolVersion == 0 {
		in.ProtocolVersion = 3.3
	}
	item := Light{
		ID:              uuid.NewString(),
		Name:            name,
		Room:            strings.TrimSpace(in.Room),
		DeviceID:        deviceID,
		ProtocolVersion: in.ProtocolVersion,
		SetupStatus:     "pending",
		Enabled:         true,
		CreatedAt:       time.Now().UTC(),
		UpdatedAt:       time.Now().UTC(),
	}
	created, err := s.repo.Create(ctx, item, Credentials{Secret: key, Address: strings.TrimSpace(in.Address)})
	if err != nil {
		return Light{}, err
	}
	s.mu.Lock()
	s.states[created.ID] = State{Online: false}
	s.mu.Unlock()
	s.broadcast("light.created", created)
	s.provision(created.ID)
	return s.Get(ctx, created.ID)
}

// Update renames a light or replaces its credentials, validating new
// credentials before they replace a working configuration.
func (s *Service) Update(ctx context.Context, id string, in UpdateInput) (Light, error) {
	unlock := s.lock(id)
	defer unlock()
	_, credentials, err := s.repo.Get(ctx, id)
	if err != nil {
		return Light{}, err
	}
	if in.Name != nil {
		name := strings.TrimSpace(*in.Name)
		if name == "" {
			return Light{}, errors.New("o nome da lâmpada não pode ficar vazio")
		}
		in.Name = &name
	}
	if in.Room != nil {
		room := strings.TrimSpace(*in.Room)
		in.Room = &room
	}
	if in.LocalKey != nil {
		key := strings.TrimSpace(*in.LocalKey)
		if len(key) != 16 {
			return Light{}, errors.New("a chave local tem 16 caracteres")
		}
		in.LocalKey = &key
	}
	updated, err := s.repo.Update(ctx, id, in, &credentials)
	if err != nil {
		return Light{}, err
	}
	s.broadcast("light.updated", updated)
	if in.LocalKey != nil || in.Address != nil || in.ProtocolVersion != nil {
		s.provision(id)
		return s.Get(ctx, id)
	}
	return updated, nil
}

// EnsureProvisioned validates a light in the background and stores what the
// device reported: the working protocol version, the data point layout and the
// capabilities it exposes.
func (s *Service) provision(id string) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		unlock := s.lock(id)
		defer unlock()
		item, credentials, err := s.repo.Get(ctx, id)
		if err != nil {
			return
		}
		scanner, ok := s.Scanner()
		if !ok {
			return
		}
		_ = s.repo.SetSetupStatus(ctx, id, "pending", "")
		result, err := scanner.Provision(ctx, item, credentials)
		if err != nil {
			s.markOffline(id)
			_ = s.repo.SetSetupStatus(ctx, id, "failed", provisionError(err))
			if updated, getErr := s.Get(ctx, id); getErr == nil {
				s.hub.Broadcast(map[string]any{"type": "light.updated", "light": updated})
			}
			return
		}
		_ = s.repo.SetCapabilities(ctx, id, result.Capabilities, result.Mapping)
		if result.Version > 0 && result.Version != item.ProtocolVersion {
			patched := result.Version
			_, _ = s.repo.Update(ctx, id, UpdateInput{ProtocolVersion: &patched}, nil)
		}
		s.storeState(id, result.State)
		_ = s.repo.SetConnection(ctx, id, credentials.Address, "ready", "", true)
		if updated, getErr := s.Get(ctx, id); getErr == nil {
			s.hub.Broadcast(map[string]any{"type": "light.updated", "light": updated})
		}
	}()
}

func provisionError(err error) string {
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return "Lâmpada não encontrada."
	case errors.Is(err, ErrNoLightingAdapter):
		return "Nenhum adaptador de iluminação local está ativo."
	default:
		return "Não foi possível falar com a lâmpada: confira a chave local, o endereço na rede e se ela está ligada."
	}
}

func (s *Service) List(ctx context.Context) ([]Light, error) {
	items, err := s.repo.List(ctx)
	if err != nil {
		return nil, err
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for i := range items {
		if state, ok := s.states[items[i].ID]; ok {
			items[i].State = state
		}
	}
	return items, nil
}

func (s *Service) Get(ctx context.Context, id string) (Light, error) {
	item, _, err := s.repo.Get(ctx, id)
	if err != nil {
		return item, err
	}
	s.mu.RLock()
	item.State = s.states[id]
	s.mu.RUnlock()
	return item, nil
}

func (s *Service) Delete(ctx context.Context, id string) error {
	s.mu.Lock()
	delete(s.states, id)
	s.mu.Unlock()
	return s.repo.Delete(ctx, id)
}

// broadcast publishes a change through the realtime hub when one is attached.
func (s *Service) broadcast(kind string, payload any) {
	if s.hub == nil {
		return
	}
	s.hub.Broadcast(map[string]any{"type": kind, "light": payload})
}

// SetFavorite pins or unpins the light on the overview.
func (s *Service) SetFavorite(ctx context.Context, id string, favorite bool) error {
	if err := s.repo.SetFavorite(ctx, id, favorite); err != nil {
		return err
	}
	updated, err := s.Get(ctx, id)
	if err == nil {
		s.hub.Broadcast(map[string]any{"type": "light.updated", "light": updated})
	}
	return err
}

func (s *Service) Control(ctx context.Context, id string, patch StatePatch) (Light, error) {
	unlock := s.lock(id)
	defer unlock()
	item, cred, err := s.repo.Get(ctx, id)
	if err != nil {
		return item, err
	}
	if !item.Enabled {
		return item, fmt.Errorf("light is disabled")
	}
	if !s.Available() {
		return item, ErrNoLightingAdapter
	}
	if cred.Address == "" {
		if _, err = s.discover(ctx, item, cred); err != nil {
			return item, err
		}
		item, cred, err = s.repo.Get(ctx, id)
		if err != nil {
			return item, err
		}
	}
	state, err := s.driver.Apply(ctx, item, cred, patch)
	if err != nil {
		if _, discoverErr := s.discover(ctx, item, cred); discoverErr == nil {
			item, cred, _ = s.repo.Get(ctx, id)
			state, err = s.driver.Apply(ctx, item, cred, patch)
		}
	}
	if err != nil {
		s.markOffline(id)
		_ = s.repo.SetConnection(ctx, id, "", "failed", "Não foi possível alcançar a lâmpada na rede local.", false)
		return item, err
	}
	s.storeState(id, state)
	_ = s.repo.SetConnection(ctx, id, cred.Address, "ready", "", true)
	updated, _ := s.Get(ctx, id)
	s.hub.Broadcast(map[string]any{"type": "light.updated", "light": updated})
	return updated, nil
}

func (s *Service) Refresh(ctx context.Context, id string) (Light, error) {
	unlock := s.lock(id)
	defer unlock()
	item, cred, err := s.repo.Get(ctx, id)
	if err != nil {
		return item, err
	}
	if !s.Available() {
		return item, ErrNoLightingAdapter
	}
	if cred.Address == "" {
		if _, err = s.discover(ctx, item, cred); err != nil {
			s.markOffline(id)
			_ = s.repo.SetConnection(ctx, id, "", "failed", "Lâmpada não encontrada na rede local.", false)
			return item, err
		}
		item, cred, _ = s.repo.Get(ctx, id)
	}
	state, err := s.driver.Status(ctx, item, cred)
	if err != nil {
		if _, discoverErr := s.discover(ctx, item, cred); discoverErr == nil {
			item, cred, _ = s.repo.Get(ctx, id)
			state, err = s.driver.Status(ctx, item, cred)
		}
	}
	if err != nil {
		s.markOffline(id)
		_ = s.repo.SetConnection(ctx, id, "", "failed", "Lâmpada indisponível na rede local.", false)
		return item, err
	}
	s.storeState(id, state)
	_ = s.repo.SetConnection(ctx, id, cred.Address, "ready", "", true)
	return s.Get(ctx, id)
}

func (s *Service) discover(ctx context.Context, item Light, cred Credentials) (string, error) {
	address, err := s.driver.Discover(ctx, item, cred)
	if err != nil {
		return "", err
	}
	err = s.repo.SetConnection(ctx, item.ID, address, "pending", "", false)
	return address, err
}

func (s *Service) Run(ctx context.Context) {
	if !s.Available() {
		return
	}
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	s.refreshAll(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.refreshAll(ctx)
		}
	}
}
func (s *Service) refreshAll(ctx context.Context) {
	items, err := s.repo.List(ctx)
	if err != nil {
		return
	}
	for _, item := range items {
		if !item.Enabled {
			continue
		}
		item := item
		go func() {
			poll, cancel := context.WithTimeout(ctx, 8*time.Second)
			defer cancel()
			if _, err := s.Refresh(poll, item.ID); err != nil && err != sql.ErrNoRows {
				s.logger.Debug("refresh light", "light", item.ID, "error", err)
			}
		}()
	}
}
func (s *Service) storeState(id string, state State) {
	s.mu.Lock()
	previous := s.states[id]
	s.states[id] = state
	s.mu.Unlock()
	if previous != state {
		s.hub.Broadcast(map[string]any{"type": "light.state", "lightId": id, "state": state})
	}
}
func (s *Service) markOffline(id string) {
	s.mu.Lock()
	state := s.states[id]
	state.Online = false
	state.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	s.states[id] = state
	s.mu.Unlock()
}
func (s *Service) lock(id string) func() {
	value, _ := s.locks.LoadOrStore(id, &sync.Mutex{})
	mutex := value.(*sync.Mutex)
	mutex.Lock()
	return mutex.Unlock
}
