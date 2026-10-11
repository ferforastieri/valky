package remote

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

type Broadcaster interface{ Broadcast(any) }

// Service coordinates persistence and the protocol driver. Pairing and key
// presses share a single connection lifecycle per device so two quick taps do
// not race the TV's authorization prompts.
type Service struct {
	repo   *Repository
	driver Driver
	hub    Broadcaster
	logger *slog.Logger
	locks  sync.Map
}

func NewService(repo *Repository, driver Driver, hub Broadcaster, logger *slog.Logger) *Service {
	return &Service{repo: repo, driver: driver, hub: hub, logger: logger}
}

func (s *Service) Available() bool { return s.driver != nil && s.driver.Available() }

func (s *Service) List(ctx context.Context) ([]Device, error) {
	return s.repo.List(ctx)
}

func (s *Service) Get(ctx context.Context, id string) (Device, error) {
	item, _, err := s.repo.Get(ctx, id)
	return item, err
}

func (s *Service) Create(ctx context.Context, in CreateInput) (Device, error) {
	name := strings.TrimSpace(in.Name)
	if name == "" {
		return Device{}, fmt.Errorf("informe um nome para o controle remoto")
	}
	kind := strings.ToLower(strings.TrimSpace(in.Kind))
	if kind == "" {
		kind = KindSamsung
	}
	if kind != KindSamsung {
		return Device{}, fmt.Errorf("tipo de controle remoto não suportado: %s", kind)
	}
	host, port, err := normalizeAddress(in.Host, in.Port)
	if err != nil {
		return Device{}, err
	}
	device := Device{
		ID: uuid.NewString(), Name: name, Kind: kind, Host: host, Port: port,
		SetupStatus: "pending", Enabled: true,
	}
	created, err := s.repo.Create(ctx, device, "")
	if err != nil {
		return Device{}, err
	}
	s.broadcast("remote.created", created)
	return created, nil
}

func (s *Service) Update(ctx context.Context, id string, in UpdateInput) (Device, error) {
	if in.Host != nil && strings.TrimSpace(*in.Host) != "" {
		host, port, err := normalizeAddress(*in.Host, 0)
		if err != nil {
			return Device{}, err
		}
		trimmedHost := host
		in.Host = &trimmedHost
		if in.Port == nil || *in.Port == 0 {
			in.Port = &port
		}
	}
	if in.Name != nil {
		trimmed := strings.TrimSpace(*in.Name)
		in.Name = &trimmed
	}
	updated, err := s.repo.Update(ctx, id, in)
	if err != nil {
		return Device{}, err
	}
	s.broadcast("remote.updated", updated)
	return updated, nil
}

func (s *Service) Delete(ctx context.Context, id string) error {
	if err := s.repo.Delete(ctx, id); err != nil {
		return err
	}
	s.broadcast("remote.deleted", map[string]string{"remoteId": id})
	return nil
}

// Pair asks the TV to authorize this controller. The user must accept the
// on-screen prompt; the token returned afterwards is stored encrypted.
func (s *Service) Pair(ctx context.Context, id string) (Device, error) {
	unlock := s.lock(id)
	defer unlock()
	item, _, err := s.repo.Get(ctx, id)
	if err != nil {
		return Device{}, err
	}
	if !s.Available() {
		return Device{}, ErrNoRemoteAdapter
	}
	_ = s.repo.SetStatus(ctx, id, "pending", "", false)
	pairCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	token, err := s.driver.Pair(pairCtx, item)
	if err != nil {
		_ = s.repo.SetStatus(ctx, id, "failed", err.Error(), false)
		return s.Get(ctx, id)
	}
	if err := s.repo.SetToken(ctx, id, token); err != nil {
		return Device{}, err
	}
	updated, err := s.Get(ctx, id)
	if err == nil {
		s.broadcast("remote.updated", updated)
	}
	return updated, err
}

func (s *Service) Send(ctx context.Context, id, key string) (Device, error) {
	key = strings.ToUpper(strings.TrimSpace(key))
	if !validKey(key) {
		return Device{}, fmt.Errorf("comando não suportado: %s", key)
	}
	unlock := s.lock(id)
	defer unlock()
	item, credentials, err := s.repo.Get(ctx, id)
	if err != nil {
		return Device{}, err
	}
	if !item.Enabled {
		return Device{}, fmt.Errorf("este controle remoto está desativado")
	}
	if credentials.Token == "" {
		return Device{}, fmt.Errorf("pareie o controle com a TV antes de enviar comandos")
	}
	if !s.Available() {
		return Device{}, ErrNoRemoteAdapter
	}
	sendCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	if err = s.driver.Send(sendCtx, item, credentials.Token, key); err != nil {
		_ = s.repo.SetStatus(ctx, id, "failed", err.Error(), false)
		if updated, getErr := s.Get(ctx, id); getErr == nil {
			s.broadcast("remote.updated", updated)
		}
		return Device{}, err
	}
	_ = s.repo.SetStatus(ctx, id, "ready", "", true)
	updated, err := s.Get(ctx, id)
	if err == nil {
		s.broadcast("remote.updated", updated)
	}
	return updated, err
}

func (s *Service) broadcast(kind string, payload any) {
	if s.hub != nil {
		s.hub.Broadcast(map[string]any{"type": kind, "device": payload})
	}
}

func (s *Service) lock(id string) func() {
	value, _ := s.locks.LoadOrStore(id, &sync.Mutex{})
	mutex := value.(*sync.Mutex)
	mutex.Lock()
	return mutex.Unlock
}

// normalizeAddress accepts an IPv4/IPv6/hostname, with or without a scheme or
// an explicit port, and returns the canonical host and port to store.
func normalizeAddress(raw string, port int) (string, int, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", 0, fmt.Errorf("informe o endereço da TV")
	}
	value = strings.TrimPrefix(strings.TrimPrefix(value, "wss://"), "ws://")
	value = strings.TrimPrefix(strings.TrimPrefix(value, "https://"), "http://")
	if index := strings.IndexAny(value, "/ "); index >= 0 {
		value = value[:index]
	}
	host := value
	if hostOnly, portOnly, err := net.SplitHostPort(value); err == nil {
		host = hostOnly
		if parsed, parseErr := strconv.Atoi(portOnly); parseErr == nil && parsed > 0 && parsed <= 65535 {
			port = parsed
		}
	}
	host = strings.Trim(host, "[]")
	if host == "" {
		return "", 0, fmt.Errorf("informe o endereço da TV")
	}
	if port == 0 {
		port = DefaultPort
	}
	if port < 1 || port > 65535 {
		return "", 0, fmt.Errorf("porta inválida para o controle remoto")
	}
	return host, port, nil
}
