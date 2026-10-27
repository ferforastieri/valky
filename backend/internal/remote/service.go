package remote

import (
	"context"
	"errors"
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
	// offline guarda o último erro de conexão por alguns segundos. Sem isso,
	// cada toque numa TV desligada gastaria o tempo do dial e a fila inteira
	// estouraria o limite do Cloudflare, que devolve uma página de erro enorme.
	offline sync.Map
	// scans evita varrer a rede a cada toque enquanto a TV continua sumida.
	scans sync.Map
	// scan é a varredura da faixa; fica no campo para o teste não tocar a rede.
	scan func(context.Context, string, []int, time.Duration) []string
}

// offlineMark é a janela em que o backend responde na hora, sem tentar a TV.
type offlineMark struct {
	until   time.Time
	message string
}

func NewService(repo *Repository, driver Driver, hub Broadcaster, logger *slog.Logger) *Service {
	return &Service{repo: repo, driver: driver, hub: hub, logger: logger, scan: scanSubnet}
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
	s.clearOffline(id)
	s.broadcast("remote.updated", updated)
	return updated, nil
}

func (s *Service) Delete(ctx context.Context, id string) error {
	if err := s.repo.Delete(ctx, id); err != nil {
		return err
	}
	s.clearOffline(id)
	s.broadcast("remote.deleted", map[string]string{"remoteId": id})
	return nil
}

// Pair asks the TV to authorize this controller. The user must accept the
// on-screen prompt; the token returned afterwards is stored encrypted.
func (s *Service) Pair(ctx context.Context, id string) (Device, error) {
	unlock, err := s.lock(ctx, id)
	if err != nil {
		return Device{}, err
	}
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
		s.clearOffline(id)
		s.broadcast("remote.updated", updated)
	}
	return updated, err
}

func (s *Service) Send(ctx context.Context, id, key string) (Device, error) {
	key = strings.ToUpper(strings.TrimSpace(key))
	if !validKey(key) {
		return Device{}, fmt.Errorf("comando não suportado: %s", key)
	}
	unlock, err := s.lock(ctx, id)
	if err != nil {
		return Device{}, err
	}
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
	if offline, ok := s.offlineNow(id); ok {
		return Device{}, offline
	}
	sendCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	err = s.driver.Send(sendCtx, item, credentials.Token, key)
	cancel()
	if err != nil {
		// O DHCP pode ter trocado o endereço da TV: procura pelo MAC antes de
		// devolver o erro, como a lâmpada faz quando muda de IP.
		if updated, moved := s.recoverAddress(ctx, item); moved {
			item = updated
			retryCtx, retryCancel := context.WithTimeout(ctx, 4*time.Second)
			err = s.driver.Send(retryCtx, item, credentials.Token, key)
			retryCancel()
		}
		if err != nil {
			s.markOffline(id, err)
			_ = s.repo.SetStatus(ctx, id, "failed", err.Error(), false)
			if updated, getErr := s.Get(ctx, id); getErr == nil {
				s.broadcast("remote.updated", updated)
			}
			return Device{}, err
		}
	}
	s.clearOffline(id)
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

// SendText types text into the TV's focused field.
func (s *Service) SendText(ctx context.Context, id, text string) (Device, error) {
	// Espaço, apagar (\\b) e enter (\\n) são teclas de verdade na TV: só o
	// vazio é recusado. Nada de aparar o texto — era o que comia a barra de
	// espaço e o enter do teclado do app e do widget.
	if text == "" {
		return Device{}, fmt.Errorf("informe o texto")
	}
	if len(text) > 64 {
		return Device{}, fmt.Errorf("o texto é muito longo")
	}
	unlock, err := s.lock(ctx, id)
	if err != nil {
		return Device{}, err
	}
	defer unlock()
	item, credentials, err := s.repo.Get(ctx, id)
	if err != nil {
		return Device{}, err
	}
	if !item.Enabled {
		return Device{}, fmt.Errorf("este controle remoto está desativado")
	}
	if credentials.Token == "" {
		return Device{}, fmt.Errorf("pareie o controle com a TV antes de enviar texto")
	}
	if !s.Available() {
		return Device{}, ErrNoRemoteAdapter
	}
	if offline, ok := s.offlineNow(id); ok {
		return Device{}, offline
	}
	sendCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	err = s.driver.SendText(sendCtx, item, credentials.Token, text)
	cancel()
	if err != nil {
		if updated, moved := s.recoverAddress(ctx, item); moved {
			item = updated
			retryCtx, retryCancel := context.WithTimeout(ctx, 4*time.Second)
			err = s.driver.SendText(retryCtx, item, credentials.Token, text)
			retryCancel()
		}
		if err != nil {
			s.markOffline(id, err)
			_ = s.repo.SetStatus(ctx, id, "failed", err.Error(), false)
			if updated, getErr := s.Get(ctx, id); getErr == nil {
				s.broadcast("remote.updated", updated)
			}
			return Device{}, err
		}
	}
	s.clearOffline(id)
	if item.SetupStatus != "ready" || item.SetupError != "" {
		_ = s.repo.SetStatus(ctx, id, "ready", "", true)
		item.SetupStatus = "ready"
		item.SetupError = ""
	}
	return item, nil
}

// Wake sends a Wake-on-LAN magic packet so a standby TV powers on.
func (s *Service) Wake(ctx context.Context, id string) (Device, error) {
	unlock, err := s.lock(ctx, id)
	if err != nil {
		return Device{}, err
	}
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
	unlock, err := s.lock(ctx, id)
	if err != nil {
		return Device{}, false, err
	}
	defer unlock()
	item, credentials, err := s.repo.Get(ctx, id)
	if err != nil {
		return Device{}, false, err
	}
	if !s.Available() {
		return Device{}, false, ErrNoRemoteAdapter
	}
	if credentials.Token != "" && !s.driver.Reachable(ctx, item) {
		// A TV pode ter mudado de endereço: se reencontrarmos pelo MAC, o botão
		// desliga em vez de mandar um magic packet para o vazio.
		if updated, moved := s.recoverAddress(ctx, item); moved {
			item = updated
		}
	}
	if credentials.Token != "" && s.driver.Reachable(ctx, item) {
		sendCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
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

// offlineNow devolve o erro guardado se a TV falhou há poucos segundos. É o que
// mantém uma rajada de toques instantânea em vez de enfileirar tentativas que
// o Cloudflare corta com uma página de erro.
func (s *Service) offlineNow(id string) (error, bool) {
	value, ok := s.offline.Load(id)
	if !ok {
		return nil, false
	}
	mark := value.(offlineMark)
	if time.Now().Before(mark.until) {
		return errors.New(mark.message), true
	}
	s.offline.Delete(id)
	return nil, false
}

func (s *Service) markOffline(id string, err error) {
	if err == nil {
		return
	}
	s.offline.Store(id, offlineMark{until: time.Now().Add(8 * time.Second), message: err.Error()})
}

func (s *Service) clearOffline(id string) {
	s.offline.Delete(id)
	s.scans.Delete(id)
}

// recoverAddress varre a faixa do endereço salvo atrás da TV e confere cada
// candidato pelo MAC antes de gravar o novo IP. Roda só no caminho de falha,
// com espera curta, para não atrasar o toque quando a TV está desligada.
func (s *Service) recoverAddress(ctx context.Context, item Device) (Device, bool) {
	mac := strings.TrimSpace(item.MAC)
	if mac == "" {
		return item, false
	}
	if value, ok := s.scans.Load(item.ID); ok {
		if time.Since(value.(time.Time)) < 15*time.Second {
			return item, false
		}
	}
	s.scans.Store(item.ID, time.Now())
	scan := s.scan
	if scan == nil {
		scan = scanSubnet
	}
	scanCtx, cancel := context.WithTimeout(ctx, 2500*time.Millisecond)
	addresses := scan(scanCtx, item.Host, []int{LegacyPort, DefaultPort}, 250*time.Millisecond)
	cancel()
	for _, address := range addresses {
		if address == item.Host {
			continue
		}
		probeCtx, probeCancel := context.WithTimeout(ctx, 1500*time.Millisecond)
		found, err := s.driver.DeviceMAC(probeCtx, Device{Host: address, Port: LegacyPort})
		probeCancel()
		if err != nil || !sameMAC(found, mac) {
			continue
		}
		host := address
		updated, err := s.repo.Update(ctx, item.ID, UpdateInput{Host: &host})
		if err != nil {
			return item, false
		}
		if s.logger != nil {
			s.logger.Info("TV reencontrada na rede", "remote", item.Name, "de", item.Host, "para", address)
		}
		s.broadcast("remote.updated", updated)
		return updated, true
	}
	return item, false
}

// lock serializa os comandos por aparelho, mas respeita o cancelamento do
// cliente: um toque que o app já desistiu não fica na fila segurando os outros.
func (s *Service) lock(ctx context.Context, id string) (func(), error) {
	value, _ := s.locks.LoadOrStore(id, make(chan struct{}, 1))
	slot := value.(chan struct{})
	select {
	case slot <- struct{}{}:
		return func() { <-slot }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
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
