package light

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"sync"
	"time"
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

func (s *Service) Create(ctx context.Context, input CreateInput) (Light, error) {
	item, err := s.repo.Create(ctx, input)
	if err != nil {
		return item, err
	}
	updated, probeErr := s.Refresh(ctx, item.ID)
	if probeErr != nil {
		updated, _ = s.Get(ctx, item.ID)
	}
	return updated, nil
}

func (s *Service) Update(ctx context.Context, id string, input UpdateInput) (Light, error) {
	item, err := s.repo.Update(ctx, id, input)
	if err != nil {
		return item, err
	}
	updated, probeErr := s.Refresh(ctx, item.ID)
	if probeErr != nil {
		updated, _ = s.Get(ctx, item.ID)
	}
	return updated, nil
}

func (s *Service) Delete(ctx context.Context, id string) error {
	s.mu.Lock()
	delete(s.states, id)
	s.mu.Unlock()
	return s.repo.Delete(ctx, id)
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
	if cred.LastIP == "" {
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
	_ = s.repo.SetConnection(ctx, id, cred.LastIP, "ready", "", true)
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
	if cred.LastIP == "" {
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
	_ = s.repo.SetConnection(ctx, id, cred.LastIP, "ready", "", true)
	return s.Get(ctx, id)
}

func (s *Service) discover(ctx context.Context, item Light, cred Credentials) (string, error) {
	ip, version, err := s.driver.Discover(ctx, item, cred)
	if err != nil {
		return "", err
	}
	if version != item.ProtocolVersion {
		_, err = s.repo.Update(ctx, item.ID, UpdateInput{Name: item.Name, Room: item.Room, IP: ip, ProtocolVersion: version, Enabled: item.Enabled})
	} else {
		err = s.repo.SetConnection(ctx, item.ID, ip, "pending", "", false)
	}
	return ip, err
}

func (s *Service) Run(ctx context.Context) {
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
