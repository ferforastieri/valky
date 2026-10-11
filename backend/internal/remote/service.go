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
		kind = KindSmartTV
	}
	if len(kind) > 32 {
		return Device{}, fmt.Errorf("tipo de controle remoto inválido")
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
	// The TV is reachable now: capture its MAC so Wake-on-LAN works later.
	macCtx, macCancel := context.WithTimeout(ctx, 8*time.Second)
	if mac, macErr := s.driver.DeviceMAC(macCtx, item); macErr == nil && strings.TrimSpace(mac) != "" {
		_ = s.repo.SetMAC(ctx, id, strings.TrimSpace(mac))
	}
	macCancel()
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
	// Key presses are high frequency, so the success path stays free of database
	// writes and broadcasts; the status is only persisted when it actually
	// changes, which keeps the remote responsive.
	if item.SetupStatus != "ready" || item.SetupError != "" {
		_ = s.repo.SetStatus(ctx, id, "ready", "", true)
		item.SetupStatus = "ready"
		item.SetupError = ""
	}
	return item, nil
}

// Wake sends a Wake-on-LAN magic packet so a standby TV powers on.
func (s *Service) Wake(ctx context.Context, id string) (Device, error) {
	unlock := s.lock(id)
	defer unlock()
	item, _, err := s.repo.Get(ctx, id)
	if err != nil {
		return Device{}, err
	}
	return s.wakeLocked(ctx, item)
}

// Power toggles the TV with a single action: a reachable TV is powered off and
// a standby TV is woken over the network. The returned flag reports whether the
// TV was turned on (true) or off (false).
func (s *Service) Power(ctx context.Context, id string) (Device, bool, error) {
	unlock := s.lock(id)
	defer unlock()
	item, credentials, err := s.repo.Get(ctx, id)
	if err != nil {
		return Device{}, false, err
	}
	if !s.Available() {
		return Device{}, false, ErrNoRemoteAdapter
	}
	if credentials.Token != "" && s.driver.Reachable(ctx, item) {
		sendCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
		defer cancel()
		if err := s.driver.Send(sendCtx, item, credentials.Token, "KEY_POWER"); err != nil {
			_ = s.repo.SetStatus(ctx, id, "failed", err.Error(), false)
			return Device{}, false, err
		}
		_ = s.repo.SetStatus(ctx, id, "ready", "", true)
		updated, getErr := s.Get(ctx, id)
		if getErr == nil {
			s.broadcast("remote.updated", updated)
		}
		return updated, false, getErr
	}
	updated, err := s.wakeLocked(ctx, item)
	if err != nil {
		return Device{}, false, err
	}
	return updated, true, nil
}

func (s *Service) wakeLocked(ctx context.Context, item Device) (Device, error) {
	mac := strings.TrimSpace(item.MAC)
	if mac == "" {
		macCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		if fetched, macErr := s.driver.DeviceMAC(macCtx, item); macErr == nil {
			mac = strings.TrimSpace(fetched)
		}
		cancel()
	}
	if mac == "" {
		return Device{}, fmt.Errorf("não foi possível ligar a TV: verifique se ela está na tomada e conectada à rede")
	}
	if err := sendMagicPacket(mac, item.Host); err != nil {
		return Device{}, err
	}
	if mac != item.MAC {
		_ = s.repo.SetMAC(ctx, item.ID, mac)
	}
	updated, err := s.Get(ctx, item.ID)
	if err == nil {
		s.broadcast("remote.updated", updated)
	}
	return updated, err
}

// SetFavorite pins or unpins the device on the overview.
func (s *Service) SetFavorite(ctx context.Context, id string, favorite bool) (Device, error) {
	if err := s.repo.SetFavorite(ctx, id, favorite); err != nil {
		return Device{}, err
	}
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

// sendMagicPacket broadcasts the Wake-on-LAN magic packet. It targets the
// limited broadcast, the subnet broadcast and the TV address so both wired and
// Wi-Fi wake-ups are covered.
func sendMagicPacket(mac, host string) error {
	hw, err := net.ParseMAC(mac)
	if err != nil {
		return fmt.Errorf("MAC inválido: %w", err)
	}
	packet := make([]byte, 6+16*len(hw))
	for i := 0; i < 6; i++ {
		packet[i] = 0xff
	}
	for i := 0; i < 16; i++ {
		copy(packet[6+i*len(hw):], hw)
	}
	targets := []string{"255.255.255.255"}
	if ip := net.ParseIP(host).To4(); ip != nil {
		targets = append(targets, ip.String())
		if mask := net.CIDRMask(24, 32); mask != nil {
			broadcast := make(net.IP, len(ip))
			for i := range ip {
				broadcast[i] = ip[i] | ^mask[i]
			}
			targets = append(targets, broadcast.String())
		}
	}
	var lastErr error
	for _, target := range targets {
		address := &net.UDPAddr{IP: net.ParseIP(target), Port: 9}
		if address.IP == nil {
			continue
		}
		conn, dialErr := net.DialUDP("udp4", nil, address)
		if dialErr != nil {
			lastErr = dialErr
			continue
		}
		for i := 0; i < 3; i++ {
			if _, writeErr := conn.Write(packet); writeErr != nil {
				lastErr = writeErr
			}
			time.Sleep(60 * time.Millisecond)
		}
		conn.Close()
	}
	return lastErr
}
