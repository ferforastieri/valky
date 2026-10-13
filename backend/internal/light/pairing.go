package light

import (
	"context"
	"crypto/md5"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/light/tuya"
	"github.com/google/uuid"
)

// Local pairing: the phone sends the Wi-Fi credentials to a lamp that is
// blinking fast, using the over-the-air format the vendor application uses. The
// credentials never leave the phone; the backend only supplies the one-time
// values the lamp stores and then watches the network for the new device.
//
// A session is short lived and single use. While it is open, the service listens
// for devices that answer the local announcement and, for each new one, tries
// the key material the lamp should have stored. The first key that answers a
// status read becomes the light's local key and the lamp is registered.
type PairingSession struct {
	ID       string `json:"id"`
	Token    string `json:"token"`
	Secret   string `json:"secret"`
	Region   string `json:"region"`
	Status   string `json:"status"`
	Message  string `json:"message,omitempty"`
	DeviceID string `json:"deviceId,omitempty"`
	Address  string `json:"address,omitempty"`
	Light    *Light `json:"light,omitempty"`
	// Announcement é o que a lâmpada anunciou na rede quando o celular escutou.
	Announcement *Announcement `json:"announcement,omitempty"`
	ExpiresAt    time.Time     `json:"expiresAt"`
	StartedAt    time.Time     `json:"startedAt"`
	EndedAt      *time.Time    `json:"endedAt,omitempty"`
}

// Announcement é um anúncio ouvido na rede (o celular escuta o ar do Wi-Fi e
// repassa ao backend, que decodifica).
type Announcement struct {
	Address    string         `json:"address"`
	Port       int            `json:"port"`
	Fields     map[string]any `json:"fields,omitempty"`
	DeviceID   string         `json:"deviceId,omitempty"`
	Version    float64        `json:"version,omitempty"`
	Recognized bool           `json:"recognized"`
}

const (
	PairingWaiting = "waiting" // the phone is still sending the credentials
	PairingFound   = "found"   // a new device answered the local announcement
	PairingPaired  = "paired"  // the device accepted a key and was registered
	PairingFailed  = "failed"  // the window closed without a controllable device
)

// PairingWindow is how long a session stays open. The vendor application issues
// a token valid for ten minutes; five is plenty for a lamp on the same desk.
const PairingWindow = 5 * time.Minute

// A lampa guarda o token que recebeu pelo ar. Ele é reaproveitado entre janelas
// por meia hora e rotacionado depois de cada lâmpada configurada, para uma
// lâmpada que já entrou na rede continuar sendo aceita sem novo pareamento.
const (
	pairingTokenKey  = "light_pairing_token"
	pairingSecretKey = "light_pairing_secret"
	pairingRegion    = "AY"
	pairingTokenTTL  = 30 * time.Minute
)

// pendingToken returns the token a lamp may have stored, creating one when there
// is none or when it is too old.
func (s *Service) pendingToken(ctx context.Context) (token, secret string, err error) {
	read := func(key string) string {
		var value string
		_ = s.repo.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key=?`, key).Scan(&value)
		return value
	}
	token, secret = read(pairingTokenKey), read(pairingSecretKey)
	var stamp string
	_ = s.repo.store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key=?`, pairingTokenKey+"_at").Scan(&stamp)
	created, parseErr := time.Parse(time.RFC3339Nano, stamp)
	if len(token) == 16 && len(secret) == 4 && parseErr == nil && time.Since(created) < pairingTokenTTL {
		return token, secret, nil
	}
	return s.rotatePendingToken(ctx)
}

// rotatePendingToken generates the values the next lamp will store.
func (s *Service) rotatePendingToken(ctx context.Context) (string, string, error) {
	token, secret := randomToken(16), randomToken(4)
	now := time.Now().UTC().Format(time.RFC3339Nano)
	for key, value := range map[string]string{
		pairingTokenKey:         token,
		pairingSecretKey:        secret,
		pairingTokenKey + "_at": now,
	} {
		if _, err := s.repo.store.DB.ExecContext(ctx, `INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at`, key, value, now); err != nil {
			return "", "", err
		}
	}
	return token, secret, nil
}

var ErrPairingInProgress = errors.New("já existe um pareamento em andamento")

// StartPairing opens a pairing window and returns the one-time values the phone
// must send over the air.
func (s *Service) StartPairing(ctx context.Context) (PairingSession, error) {
	if _, ok := s.Scanner(); !ok {
		return PairingSession{}, ErrNoLightingAdapter
	}
	s.pairingMu.Lock()
	if s.pairing != nil && time.Now().Before(s.pairing.ExpiresAt) &&
		s.pairing.Status != PairingPaired && s.pairing.Status != PairingFailed {
		active := *s.pairing
		s.pairingMu.Unlock()
		return active, ErrPairingInProgress
	}
	window := s.pairingWindow
	if window <= 0 {
		window = PairingWindow
	}
	token, secret, err := s.pendingToken(ctx)
	if err != nil {
		return PairingSession{}, err
	}
	session := &PairingSession{
		ID:        uuid.NewString(),
		Token:     token,
		Secret:    secret,
		Region:    pairingRegion,
		Status:    PairingWaiting,
		Message:   "Envie os dados do Wi-Fi para a lâmpada pelo aplicativo.",
		StartedAt: time.Now(),
		ExpiresAt: time.Now().Add(window),
	}
	s.pairing = session
	s.pairingTried = map[string]bool{}
	// A cópia é feita sob a trava: o acompanhamento já pode estar alterando a
	// sessão quando a resposta sai.
	snapshot := *session
	s.pairingMu.Unlock()
	go s.runPairing(session.ID)
	return snapshot, nil
}

// PairingStatus reports the current state of a session.
func (s *Service) PairingStatus(id string) (PairingSession, error) {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	if s.pairing == nil || s.pairing.ID != id {
		return PairingSession{}, errors.New("pareamento não encontrado")
	}
	return *s.pairing, nil
}

// CancelPairing closes the window without registering anything.
func (s *Service) CancelPairing(id string) error {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	if s.pairing == nil || s.pairing.ID != id {
		return errors.New("pareamento não encontrado")
	}
	s.pairing = nil
	s.pairingTried = map[string]bool{}
	return nil
}

// RegisterAnnouncement recebe o que o celular ouviu na rede. Um anúncio com
// identificador já é suficiente para o backend conversar com a lâmpada e
// adotá-la, sem depender de nenhuma nuvem.
func (s *Service) RegisterAnnouncement(ctx context.Context, id, address string, port int, raw []byte) (PairingSession, error) {
	session, err := s.PairingStatus(id)
	if err != nil {
		return PairingSession{}, err
	}
	announcement := &Announcement{Address: address, Port: port}
	fields, decodeErr := tuya.DecodeAnnouncement(raw)
	if decodeErr == nil {
		announcement.Fields = fields
		announcement.Recognized = true
		candidate := candidateFromAnnouncement(address, fields)
		announcement.DeviceID = candidate.DeviceID
		announcement.Version = candidate.Version
		s.updatePairing(id, func(current *PairingSession) {
			current.Announcement = announcement
			if current.Status == PairingWaiting {
				current.Status = PairingFound
			}
			if candidate.DeviceID != "" {
				current.DeviceID = candidate.DeviceID
				current.Address = address
				current.Message = fmt.Sprintf("A lâmpada anunciou em %s (id %s, v%.1f). Testando o controle local…", address, candidate.DeviceID, candidate.Version)
			} else {
				current.Message = fmt.Sprintf("A lâmpada anunciou em %s, mas sem identificador.", address)
			}
		})
		if candidate.DeviceID == "" {
			// Sem identificador não há o que registrar, mas o anúncio fica visível.
			return s.PairingStatus(id)
		}
		if _, ok := s.Scanner(); !ok {
			return s.PairingStatus(id)
		}
		registered, err := s.tryPairingKey(ctx, session, candidate)
		if err != nil {
			s.markPairingTried(address)
			attempted := len(pairingKeyCandidates(session, candidate))
			s.logger.Info("announced device refused every key", "device", deviceFingerprint(candidate), "keys", attempted, "error", err)
			s.updatePairing(id, func(current *PairingSession) {
				current.Message = fmt.Sprintf("A lâmpada em %s aceitou a conexão, mas não aceitou nenhuma das %d chaves testadas.", address, attempted)
			})
			return s.PairingStatus(id)
		}
		s.updatePairing(id, func(current *PairingSession) {
			current.Status = PairingPaired
			current.Message = "Lâmpada configurada."
			current.DeviceID = candidate.DeviceID
			current.Address = address
			current.Light = &registered
			now := time.Now()
			current.EndedAt = &now
			current.ExpiresAt = now
		})
		return s.PairingStatus(id)
	}
	// O anúncio não foi decodificado: guarda os bytes para diagnóstico.
	s.updatePairing(id, func(current *PairingSession) { current.Announcement = announcement })
	s.logger.Debug("announcement not decoded", "address", address, "bytes", len(raw), "error", decodeErr)
	return s.PairingStatus(id)
}

// candidateFromAnnouncement traduz os campos anunciados para um candidato.
func candidateFromAnnouncement(address string, fields map[string]any) Candidate {
	candidate := Candidate{Address: address}
	if value, ok := fields["gwId"].(string); ok {
		candidate.DeviceID = value
	}
	if candidate.DeviceID == "" {
		if value, ok := fields["id"].(string); ok {
			candidate.DeviceID = value
		}
	}
	if value, ok := fields["productKey"].(string); ok {
		candidate.ProductKey = value
	}
	if value, ok := fields["mac"].(string); ok {
		candidate.MAC = value
	}
	switch value := fields["version"].(type) {
	case float64:
		candidate.Version = value
	case string:
		if parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64); err == nil {
			candidate.Version = parsed
		}
	}
	return candidate
}

func (s *Service) updatePairing(id string, apply func(*PairingSession)) {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	if s.pairing == nil || s.pairing.ID != id {
		return
	}
	apply(s.pairing)
}

func (s *Service) finishPairing(id, status, message string) {
	s.updatePairing(id, func(session *PairingSession) {
		session.Status = status
		session.Message = message
		now := time.Now()
		session.EndedAt = &now
		session.ExpiresAt = now
	})
}

// runPairing watches the local network while the window is open.
func (s *Service) runPairing(id string) {
	// Look right away: the lamp may already be on the network when the window
	// opens, and the phone starts sending the credentials at the same time.
	s.probePairingDevices(id)
	ticker := time.NewTicker(1500 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		session, err := s.PairingStatus(id)
		if err != nil {
			return
		}
		if session.Status == PairingPaired || session.Status == PairingFailed {
			return
		}
		if time.Now().After(session.ExpiresAt) {
			message := "A lâmpada não apareceu na rede. Confira se ela está piscando rápido e se o Wi-Fi informado é o de 2,4 GHz."
			if session.Address != "" {
				message = fmt.Sprintf("A lâmpada respondeu em %s, mas não aceitou a chave local. Firmwares que exigem ativação na nuvem do fabricante não permitem controle local.", session.Address)
			}
			s.finishPairing(id, PairingFailed, message)
			return
		}
		s.probePairingDevices(id)
	}
}

// probePairingDevices looks for devices that are not registered yet and tries to
// take control of them with the key material of the session.
func (s *Service) probePairingDevices(id string) {
	session, err := s.PairingStatus(id)
	if err != nil {
		return
	}
	scanner, ok := s.Scanner()
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	candidates, err := scanner.Scan(ctx, 2*time.Second, false)
	if err != nil {
		return
	}
	registered, err := s.repo.List(ctx)
	if err != nil {
		return
	}
	for _, candidate := range candidates {
		if candidate.Address == "" {
			continue
		}
		// A lâmpada pode esconder o identificador até ser configurada; o
		// endereço basta para testar a chave.

		if knownDevice(registered, candidate) || s.pairingTriedAddress(candidate.Address) {
			continue
		}
		s.updatePairing(id, func(session *PairingSession) {
			session.Status = PairingFound
			session.DeviceID = candidate.DeviceID
			session.Address = candidate.Address
			session.Message = fmt.Sprintf("Lâmpada encontrada em %s. Testando o controle local…", candidate.Address)
		})
		light, err := s.tryPairingKey(ctx, session, candidate)
		if err != nil {
			s.markPairingTried(candidate.Address)
			s.logger.Debug("pairing key refused", "device", deviceFingerprint(candidate), "error", err)
			attempted := len(pairingKeyCandidates(session, candidate))
			s.logger.Info("pairing refused every key", "device", deviceFingerprint(candidate), "keys", attempted, "error", err)
			s.updatePairing(id, func(session *PairingSession) {
				session.Message = fmt.Sprintf(
					"A lâmpada em %s (id %s) respondeu na rede, mas não aceitou nenhuma das %d chaves testadas. Firmwares que exigem ativação na nuvem do fabricante não permitem controle local.",
					candidate.Address, displayDeviceID(candidate), attempted,
				)
			})
			continue
		}
		s.updatePairing(id, func(session *PairingSession) {
			session.Status = PairingPaired
			session.Message = "Lâmpada configurada."
			session.DeviceID = candidate.DeviceID
			session.Address = candidate.Address
			session.Light = &light
			now := time.Now()
			session.EndedAt = &now
			session.ExpiresAt = now
		})
		return
	}
}

// tryPairingKey walks the key material the lamp may have stored and returns the
// registered light as soon as one of them answers.
func (s *Service) tryPairingKey(ctx context.Context, session PairingSession, candidate Candidate) (Light, error) {
	scanner, _ := s.Scanner()
	var lastErr error
	for _, key := range pairingKeyCandidates(session, candidate) {
		attempt, cancel := context.WithTimeout(ctx, 5*time.Second)
		result, err := scanner.Provision(attempt, Light{DeviceID: candidate.DeviceID, ProtocolVersion: candidate.Version}, Credentials{Secret: key, Address: candidate.Address})
		cancel()
		if err != nil {
			lastErr = err
			continue
		}
		registered, err := s.registerPairedLight(ctx, candidate, session, key, result)
		if err != nil {
			return Light{}, err
		}
		return registered, nil
	}
	if lastErr == nil {
		lastErr = errors.New("a lâmpada não respondeu")
	}
	return Light{}, lastErr
}

// registerPairedLight stores a lamp that accepted the generated key.
func (s *Service) registerPairedLight(ctx context.Context, candidate Candidate, session PairingSession, key string, result ProvisionResult) (Light, error) {
	created, err := s.repo.Create(ctx, Light{
		ID:              uuid.NewString(),
		Name:            s.nextLightName(ctx),
		DeviceID:        deviceIdentity(candidate),
		ProtocolVersion: result.Version,
		Capabilities:    result.Capabilities,
		DPMapping:       result.Mapping,
		SetupStatus:     "ready",
		Enabled:         true,
	}, Credentials{Secret: key, Address: candidate.Address})
	if err != nil {
		return Light{}, err
	}
	state := result.State
	state.Online = true
	s.storeState(created.ID, state)
	_ = s.repo.SetConnection(ctx, created.ID, candidate.Address, "ready", "", true)
	registered, err := s.Get(ctx, created.ID)
	if err != nil {
		registered = created
	}
	s.broadcast("light.created", registered)
	// A próxima lâmpada recebe valores novos: cada uma guarda a própria chave.
	_, _, _ = s.rotatePendingToken(ctx)
	s.logger.Info("lamp paired locally", "light", registered.ID, "device", deviceFingerprint(candidate))
	return registered, nil
}

// pairingKeyCandidates lists the keys the lamp may have stored, from the most
// likely to the least. A device that was activated by the vendor cloud keeps a
// key only that cloud knows; the flow reports that instead of guessing forever.
func pairingKeyCandidates(session PairingSession, candidate Candidate) []string {
	var keys []string
	add := func(value string) {
		if len(value) != 16 {
			return
		}
		for _, existing := range keys {
			if existing == value {
				return
			}
		}
		keys = append(keys, value)
	}
	add(session.Token)
	for _, seed := range []string{
		session.Token + session.Secret,
		session.Secret + session.Token,
		session.Region + session.Token + session.Secret,
		candidate.DeviceID,
		strings.ToLower(candidate.DeviceID),
		candidate.DeviceID + session.Token,
		session.Token + candidate.DeviceID,
		candidate.MAC,
		strings.ReplaceAll(candidate.MAC, ":", ""),
	} {
		if strings.TrimSpace(seed) == "" {
			continue
		}
		sum := md5.Sum([]byte(seed))
		add(hex.EncodeToString(sum[:])[:16])
	}
	add(strings.Repeat("0", 16))
	return keys
}

func knownDevice(registered []Light, candidate Candidate) bool {
	for _, light := range registered {
		if light.DeviceID == candidate.DeviceID {
			return true
		}
		if light.LastAddress != "" && light.LastAddress == candidate.Address {
			return true
		}
	}
	return false
}

func (s *Service) lastPairingProbe() time.Time {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	return s.pairingProbed
}

func (s *Service) markPairingProbe() {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	s.pairingProbed = time.Now()
}

func (s *Service) pairingTriedAddress(address string) bool {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	return s.pairingTried[address]
}

func (s *Service) markPairingTried(address string) {
	s.pairingMu.Lock()
	defer s.pairingMu.Unlock()
	if s.pairingTried == nil {
		s.pairingTried = map[string]bool{}
	}
	s.pairingTried[address] = true
}

func (s *Service) nextLightName(ctx context.Context) string {
	items, err := s.repo.List(ctx)
	if err != nil {
		return "Lâmpada"
	}
	return fmt.Sprintf("Lâmpada %d", len(items)+1)
}

// deviceIdentity is the identifier stored for a lamp. Devices that hide the
// announced identifier keep the network address as their identity, which is
// enough to address them later.
func deviceIdentity(candidate Candidate) string {
	if candidate.DeviceID != "" {
		return candidate.DeviceID
	}
	if candidate.MAC != "" {
		return candidate.MAC
	}
	return "light-" + strings.ReplaceAll(candidate.Address, ".", "-")
}

func displayDeviceID(candidate Candidate) string {
	if candidate.DeviceID != "" {
		return candidate.DeviceID
	}
	return "não anunciado"
}

// deviceFingerprint describes a candidate for logs.
func deviceFingerprint(candidate Candidate) string {
	return fmt.Sprintf("%s/%s/v%.1f", candidate.Address, candidate.DeviceID, candidate.Version)
}

// randomToken returns a random alphanumeric value used as pairing material.
const tokenAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

func randomToken(length int) string {
	buffer := make([]byte, length)
	if _, err := rand.Read(buffer); err != nil {
		return strings.Repeat("0", length)
	}
	out := make([]byte, length)
	for i, value := range buffer {
		out[i] = tokenAlphabet[int(value)%len(tokenAlphabet)]
	}
	return string(out)
}
