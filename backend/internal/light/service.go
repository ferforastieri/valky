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

// Watchers reports how many clients are following changes right now. It is
// optional: a hub that cannot answer is treated as "somebody is watching".
type Watchers interface{ Watchers() int }

type Service struct {
	repo   *Repository
	driver Driver
	hub    Broadcaster
	logger *slog.Logger
	mu     sync.RWMutex
	states map[string]State
	locks  sync.Map

	// polled guarda quando cada lâmpada foi consultada pela última vez, para
	// uma olhada nova na API atualizar o que estiver velho na hora — sem
	// depender só do relógio.
	pollMu sync.Mutex
	polled map[string]time.Time
}

func NewService(repo *Repository, driver Driver, hub Broadcaster, logger *slog.Logger) *Service {
	return &Service{
		repo: repo, driver: driver, hub: hub, logger: logger,
		states: map[string]State{},
		polled: map[string]time.Time{},
	}
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

// Update renames a light or stops polling it. The
// local credentials belong to the registration flow and are never edited by hand.
func (s *Service) Update(ctx context.Context, id string, in UpdateInput) (Light, error) {
	unlock := s.lock(id)
	defer unlock()
	if in.Name != nil {
		name := strings.TrimSpace(*in.Name)
		if name == "" {
			return Light{}, errors.New("o nome da lâmpada não pode ficar vazio")
		}
		in.Name = &name
	}
	updated, err := s.repo.Update(ctx, id, in)
	if err != nil {
		return Light{}, err
	}
	s.broadcast("light.updated", updated)
	return s.Get(ctx, id)
}

// Register adopts a lamp that another application already configured (for
// example the manufacturer app). The administrator supplies what the local
// protocol needs — the address, the device identifier and the 16 character
// local key — and the adapter validates them against the device before anything
// is stored, so a wrong key is reported instead of creating a broken record.
func (s *Service) Register(ctx context.Context, in RegisterInput) (Light, error) {
	scanner, ok := s.Scanner()
	if !ok {
		return Light{}, ErrNoLightingAdapter
	}
	address := strings.TrimSpace(in.Address)
	deviceID := strings.TrimSpace(in.DeviceID)
	localKey := strings.TrimSpace(in.LocalKey)
	switch {
	case address == "":
		return Light{}, fmt.Errorf("%w: informe o endereço da lâmpada na rede", ErrInvalidRegistration)
	case deviceID == "":
		return Light{}, fmt.Errorf("%w: informe o identificador (device id) da lâmpada", ErrInvalidRegistration)
	case len(localKey) != 16:
		return Light{}, fmt.Errorf("%w: a chave local tem 16 caracteres", ErrInvalidRegistration)
	}
	existing, err := s.repo.List(ctx)
	if err != nil {
		return Light{}, err
	}
	for _, item := range existing {
		if item.DeviceID == deviceID {
			return Light{}, fmt.Errorf("%w: essa lâmpada já está cadastrada", ErrInvalidRegistration)
		}
	}
	result, err := scanner.Provision(ctx, Light{DeviceID: deviceID, ProtocolVersion: in.ProtocolVersion}, Credentials{Secret: localKey, Address: address})
	if err != nil {
		return Light{}, registrationError(err)
	}
	name := strings.TrimSpace(in.Name)
	if name == "" {
		name = s.nextLightName(ctx)
	}
	created, err := s.repo.Create(ctx, Light{
		ID:              uuid.NewString(),
		Name:            name,
		DeviceID:        deviceID,
		ProtocolVersion: result.Version,
		Capabilities:    result.Capabilities,
		DPMapping:       result.Mapping,
		SetupStatus:     "ready",
		Enabled:         true,
	}, Credentials{Secret: localKey, Address: address})
	if err != nil {
		return Light{}, err
	}
	state := result.State
	state.Online = true
	s.storeState(created.ID, state)
	_ = s.repo.SetConnection(ctx, created.ID, address, "ready", "", true)
	registered, err := s.Get(ctx, created.ID)
	if err != nil {
		registered = created
	}
	s.broadcast("light.created", registered)
	s.logger.Info("lamp registered from an existing configuration", "light", registered.ID, "address", address)
	return registered, nil
}

// registrationError turns a failed validation into something the administrator
// can act on. A lamp that answered and refused the key is a different problem
// from one that never answered.
func registrationError(err error) error {
	switch {
	case errors.Is(err, ErrLightKeyRefused):
		return errors.New("A lâmpada respondeu, mas recusou a chave local. Confira se copiou a chave certa (16 caracteres).")
	case errors.Is(err, ErrLightUnreachable):
		return errors.New("A lâmpada não respondeu no endereço informado. Confira o IP, se ela está ligada e na mesma rede de casa.")
	case errors.Is(err, ErrNoLightingAdapter):
		return ErrNoLightingAdapter
	default:
		return fmt.Errorf("não foi possível falar com a lâmpada: %w", err)
	}
}

func (s *Service) nextLightName(ctx context.Context) string {
	items, err := s.repo.List(ctx)
	if err != nil {
		return "Lâmpada"
	}
	return fmt.Sprintf("Lâmpada %d", len(items)+1)
}

func (s *Service) List(ctx context.Context) ([]Light, error) {
	items, err := s.repo.List(ctx)
	if err != nil {
		return nil, err
	}
	// Quem está olhando merece estado fresco: o que estiver velho é consultado
	// agora, em segundo plano. O aplicativo recebe o resultado pelo canal em
	// tempo real; o widget, na próxima atualização.
	s.warm(items)
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
	s.markPolled(id)
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
	// Só grava quando algo mudou de verdade: sem isso eram cinco UPDATEs a
	// cada vinte segundos por lâmpada, sem necessidade.
	if item.LastAddress != cred.Address || item.SetupStatus != "ready" {
		_ = s.repo.SetConnection(ctx, id, cred.Address, "ready", "", true)
	}
	return s.Get(ctx, id)
}

// WarmAfter é o tempo a partir do qual o estado guardado é considerado velho.
const WarmAfter = 10 * time.Second

// warm consulta em segundo plano as lâmpadas cujo estado passou de WarmAfter.
func (s *Service) warm(items []Light) {
	if !s.Available() {
		return
	}
	for _, item := range items {
		if !item.Enabled || !s.stale(item.ID) {
			continue
		}
		id := item.ID
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
			defer cancel()
			_, _ = s.Refresh(ctx, id)
		}()
	}
}

func (s *Service) stale(id string) bool {
	s.pollMu.Lock()
	defer s.pollMu.Unlock()
	return time.Since(s.polled[id]) > WarmAfter
}

func (s *Service) markPolled(id string) {
	s.pollMu.Lock()
	s.polled[id] = time.Now()
	s.pollMu.Unlock()
}

func (s *Service) discover(ctx context.Context, item Light, cred Credentials) (string, error) {
	address, err := s.driver.Discover(ctx, item, cred)
	if err != nil {
		return "", err
	}
	err = s.repo.SetConnection(ctx, item.ID, address, "pending", "", false)
	return address, err
}

// QuietInterval is how often the lamps are polled while nobody is following the
// realtime channel. With a client connected the cadence is PollInterval.
const (
	PollInterval  = 20 * time.Second
	QuietInterval = 2 * time.Minute
)

func (s *Service) Run(ctx context.Context) {
	if !s.Available() {
		return
	}
	ticker := time.NewTicker(PollInterval)
	defer ticker.Stop()
	s.refreshAll(ctx)
	quiet := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if s.watched() {
				quiet = 0
				s.refreshAll(ctx)
				continue
			}
			// Ninguém está olhando: a ronda espaça para não acordar as
			// lâmpadas à toa, mas continua viva para o estado não envelhecer.
			quiet++
			if time.Duration(quiet)*PollInterval >= QuietInterval {
				quiet = 0
				s.refreshAll(ctx)
			}
		}
	}
}

// watched reports whether any client is following the realtime channel.
func (s *Service) watched() bool {
	watchers, ok := s.hub.(Watchers)
	if !ok {
		return true
	}
	return watchers.Watchers() > 0
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
	wasOnline := state.Online
	state.Online = false
	state.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	s.states[id] = state
	s.mu.Unlock()
	// A queda também é notícia: sem isso o aplicativo só descobria a lâmpada
	// fora do ar na próxima consulta completa.
	if wasOnline {
		s.hub.Broadcast(map[string]any{"type": "light.state", "lightId": id, "state": state})
	}
}
func (s *Service) lock(id string) func() {
	value, _ := s.locks.LoadOrStore(id, &sync.Mutex{})
	mutex := value.(*sync.Mutex)
	mutex.Lock()
	return mutex.Unlock
}
