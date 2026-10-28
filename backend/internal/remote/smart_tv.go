package remote

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// SmartTVDriver speaks the WebSocket remote-control channel used by smart TVs.
// It connects to wss://HOST:8002/api/v2/channels/samsung.remote.control, falls
// back to the legacy ws://HOST:8001 channel, and exchanges the small JSON
// envelope the TV expects. The TV shows an on-screen prompt the first time a
// controller pairs; the token it returns afterwards authorizes later key
// presses without prompts.
//
// The channel path is a fixed protocol identifier and is kept verbatim; the
// domain and API stay brand-agnostic so other TV protocols can be added as
// separate drivers.
//
// Key presses reuse a single kept-alive connection per device instead of
// dialing (and re-doing the handshake) for every tap, which is what made the
// remote feel laggy. A failed write drops the connection and retries once on a
// fresh one, so a TV that went to standby is recovered transparently.
type SmartTVDriver struct {
	applicationName string
	client          *http.Client
	logger          *slog.Logger

	mu    sync.Mutex
	conns map[string]*tvConn
}

type tvConn struct {
	conn   *websocket.Conn
	device Device
	token  string
}

func NewSmartTVDriver(logger *slog.Logger) *SmartTVDriver {
	if logger == nil {
		logger = slog.Default()
	}
	// These TVs present a self-signed certificate on the secure channel, so the
	// client validates reachability but not the certificate chain. The waits are
	// short on purpose: the TV is on the LAN, so a slow dial means it is off —
	// waiting longer only makes the app queue key presses behind a dead socket.
	transport := &http.Transport{
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: true},
		ResponseHeaderTimeout: 3 * time.Second,
		DialContext:           (&net.Dialer{Timeout: 1500 * time.Millisecond}).DialContext,
	}
	return &SmartTVDriver{
		applicationName: "Valky",
		client:          &http.Client{Transport: transport},
		logger:          logger,
		conns:           map[string]*tvConn{},
	}
}

func (*SmartTVDriver) Available() bool { return true }

func (d *SmartTVDriver) Pair(ctx context.Context, device Device) (string, error) {
	// A new pairing invalidates any cached channel for this device.
	d.dropDevice(device)
	conn, err := d.dial(ctx, device, "")
	if err != nil {
		return "", err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	deadline, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	for {
		event, token, err := readEvent(deadline, conn)
		if err != nil {
			return "", fmt.Errorf("a TV não respondeu à autorização: %w", err)
		}
		switch event {
		case "ms.channel.connect":
			if token != "" {
				return token, nil
			}
		case "ms.channel.unauthorized":
			return "", fmt.Errorf("autorização recusada na TV")
		}
	}
}

func (d *SmartTVDriver) Send(ctx context.Context, device Device, token, key string) error {
	item, err := d.ready(ctx, device, token)
	if err != nil {
		return err
	}
	if err = d.writeKey(ctx, item, key); err == nil {
		return nil
	}
	// The kept-alive channel may have been closed by the TV (standby, reboot):
	// drop it and retry once on a fresh connection before surfacing an error.
	d.drop(item)
	retry, dialErr := d.ready(ctx, device, token)
	if dialErr != nil {
		return err
	}
	if retryErr := d.writeKey(ctx, retry, key); retryErr != nil {
		d.drop(retry)
		return retryErr
	}
	return nil
}

func (d *SmartTVDriver) writeKey(ctx context.Context, item *tvConn, key string) error {
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.remote.control",
		"params": map[string]any{
			"Cmd":          "Click",
			"DataOfCmd":    key,
			"Option":       "false",
			"TypeOfRemote": "SendRemoteKey",
		},
	})
	writeCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := item.conn.Write(writeCtx, websocket.MessageText, payload); err != nil {
		return fmt.Errorf("não foi possível enviar o comando: %w", err)
	}
	return nil
}

// SendText types text into the TV's focused field using the remote-control
// channel's text input command, after announcing that a text session started.
func (d *SmartTVDriver) SendText(ctx context.Context, device Device, token, text string) error {
	item, err := d.ready(ctx, device, token)
	if err != nil {
		return err
	}
	emit, _ := json.Marshal(map[string]any{
		"method": "ms.channel.emit",
		"params": map[string]any{"event": "custom.remote.textReceived", "to": "broadcast"},
	})
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.remote.control",
		"params": map[string]any{
			"Cmd":          "SendInputString",
			"DataOfCmd":    "base64",
			"TypeOfRemote": "SendInputString",
			"Data":         base64.StdEncoding.EncodeToString([]byte(text)),
		},
	})
	writeCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := item.conn.Write(writeCtx, websocket.MessageText, emit); err != nil {
		d.drop(item)
		return fmt.Errorf("não foi possível iniciar o texto: %w", err)
	}
	if err := item.conn.Write(writeCtx, websocket.MessageText, payload); err != nil {
		d.drop(item)
		return fmt.Errorf("não foi possível enviar o texto: %w", err)
	}
	return nil
}

func (d *SmartTVDriver) dial(ctx context.Context, device Device, token string) (*websocket.Conn, error) {
	port := device.Port
	if port == 0 {
		port = DefaultPort
	}
	address := net.JoinHostPort(device.Host, strconv.Itoa(port))
	// The secure channel is required on modern models; the legacy plain channel
	// is only a fallback for older TVs or a misconfigured port.
	attempts := []struct {
		scheme string
		addr   string
	}{
		{"wss", address},
		{"ws", net.JoinHostPort(device.Host, strconv.Itoa(DefaultPort))},
		{"ws", net.JoinHostPort(device.Host, strconv.Itoa(LegacyPort))},
	}
	var lastErr error
	seen := map[string]struct{}{}
	for _, attempt := range attempts {
		key := attempt.scheme + "://" + attempt.addr
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		url := channelURL(attempt.scheme, attempt.addr, controlChannelPath, d.applicationName, token)
		conn, err := d.dialURL(ctx, url)
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("endereço inválido")
	}
	return nil, fmt.Errorf("não foi possível alcançar a TV em %s: %w", device.Host, lastErr)
}

func (d *SmartTVDriver) dialURL(ctx context.Context, url string) (*websocket.Conn, error) {
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPClient: d.client})
	if err != nil {
		return nil, err
	}
	// The installed-app list is a large JSON document; the default 32 KiB read
	// limit truncates it and made the apps bar come back empty.
	conn.SetReadLimit(4 << 20)
	return conn, nil
}

// connectReady dials the control channel and consumes the connect handshake,
// returning a connection ready to exchange messages.
func (d *SmartTVDriver) connectReady(ctx context.Context, device Device, token string) (*websocket.Conn, error) {
	conn, err := d.dial(ctx, device, token)
	if err != nil {
		return nil, err
	}
	if err := d.handshake(ctx, conn); err != nil {
		conn.Close(websocket.StatusNormalClosure, "")
		return nil, err
	}
	return conn, nil
}

func (d *SmartTVDriver) handshake(ctx context.Context, conn *websocket.Conn) error {
	handshake, cancel := context.WithTimeout(ctx, 3*time.Second)
	event, _, readErr := readEvent(handshake, conn)
	cancel()
	if event == "ms.channel.unauthorized" {
		return fmt.Errorf("a TV revogou o token; pareie novamente")
	}
	if readErr != nil {
		return fmt.Errorf("a TV não concluiu a conexão: %w", readErr)
	}
	return nil
}

// ready returns a kept-alive connection for the device, dialing one when none
// is cached yet.
func (d *SmartTVDriver) ready(ctx context.Context, device Device, token string) (*tvConn, error) {
	if item := d.cached(device, token); item != nil {
		return item, nil
	}
	conn, err := d.connectReady(ctx, device, token)
	if err != nil {
		return nil, err
	}
	return d.store(device, token, conn), nil
}

func connectionKey(device Device, token string) string {
	port := device.Port
	if port == 0 {
		port = DefaultPort
	}
	return net.JoinHostPort(device.Host, strconv.Itoa(port)) + "|" + token
}

func (d *SmartTVDriver) cached(device Device, token string) *tvConn {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.conns[connectionKey(device, token)]
}

func (d *SmartTVDriver) store(device Device, token string, conn *websocket.Conn) *tvConn {
	d.mu.Lock()
	key := connectionKey(device, token)
	if existing := d.conns[key]; existing != nil && existing.conn != conn {
		_ = existing.conn.Close(websocket.StatusNormalClosure, "")
	}
	item := &tvConn{conn: conn, device: device, token: token}
	d.conns[key] = item
	d.mu.Unlock()
	// Keep the channel healthy: reading lets the library answer the TV's
	// control frames, and a read error means the channel is gone.
	go d.drain(item)
	return item
}

func (d *SmartTVDriver) drain(item *tvConn) {
	for {
		if _, _, err := item.conn.Read(context.Background()); err != nil {
			d.drop(item)
			return
		}
	}
}

func (d *SmartTVDriver) drop(item *tvConn) {
	if item == nil {
		return
	}
	d.mu.Lock()
	key := connectionKey(item.device, item.token)
	if d.conns[key] == item {
		delete(d.conns, key)
	}
	d.mu.Unlock()
	_ = item.conn.Close(websocket.StatusNormalClosure, "")
}

func (d *SmartTVDriver) dropDevice(device Device) {
	d.mu.Lock()
	prefix := net.JoinHostPort(device.Host, strconv.Itoa(devicePort(device))) + "|"
	var victims []*tvConn
	for key, item := range d.conns {
		if strings.HasPrefix(key, prefix) {
			victims = append(victims, item)
			delete(d.conns, key)
		}
	}
	d.mu.Unlock()
	for _, item := range victims {
		_ = item.conn.Close(websocket.StatusNormalClosure, "")
	}
}

func devicePort(device Device) int {
	if device.Port == 0 {
		return DefaultPort
	}
	return device.Port
}

// Reachable reports whether the TV is powered on and answering on its control
// port. It is used to decide between powering the TV off (it is on) and sending
// a Wake-on-LAN packet (it is in standby).
func (d *SmartTVDriver) Reachable(ctx context.Context, device Device) bool {
	port := devicePort(device)
	dialCtx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	conn, err := (&net.Dialer{}).DialContext(dialCtx, "tcp", net.JoinHostPort(device.Host, strconv.Itoa(port)))
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

// DeviceMAC reads the TV network address (used for Wake-on-LAN) from its REST
// endpoint. It is best effort and only needs the TV to be powered on.
func (d *SmartTVDriver) DeviceMAC(ctx context.Context, device Device) (string, error) {
	url := fmt.Sprintf("http://%s/api/v2/", net.JoinHostPort(device.Host, strconv.Itoa(LegacyPort)))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := d.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("não foi possível ler os dados da TV: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return "", fmt.Errorf("a TV respondeu %s", resp.Status)
	}
	var info struct {
		Device struct {
			NetworkType string `json:"networkType"`
			WiFiMAC     string `json:"wifiMac"`
			MAC         string `json:"mac"`
		} `json:"device"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&info); err != nil {
		return "", err
	}
	// O Wake-on-LAN precisa do MAC da interface que está ligada: uma TV no cabo
	// não acorda com o magic packet endereçado ao MAC do Wi-Fi.
	mac := pickMAC(info.Device.NetworkType, info.Device.WiFiMAC, info.Device.MAC)
	if mac == "" {
		return "", fmt.Errorf("a TV não informou o MAC")
	}
	return mac, nil
}

// pickMAC escolhe o MAC da interface ativa. Sem a informação de rede (modelos
// antigos), mantém o comportamento anterior e prefere o Wi-Fi.
func pickMAC(networkType, wifiMAC, ethernetMAC string) string {
	wired := strings.EqualFold(strings.TrimSpace(networkType), "wired")
	mac := strings.TrimSpace(ethernetMAC)
	if !wired || mac == "" {
		mac = strings.TrimSpace(wifiMAC)
	}
	if mac == "" {
		mac = strings.TrimSpace(ethernetMAC)
	}
	return mac
}

const controlChannelPath = "/api/v2/channels/samsung.remote.control"

// channelURL builds a channel URL. The application name travels base64 encoded
// exactly as the TV expects it in the query string.
func channelURL(scheme, address, path, applicationName, token string) string {
	url := fmt.Sprintf("%s://%s%s", scheme, address, path)
	if applicationName == "" {
		return url
	}
	url += "?name=" + base64.StdEncoding.EncodeToString([]byte(applicationName))
	if token != "" {
		url += "&token=" + token
	}
	return url
}

type tvEvent struct {
	Event string `json:"event"`
	Data  struct {
		Token string `json:"token"`
	} `json:"data"`
}

func readEvent(ctx context.Context, conn *websocket.Conn) (string, string, error) {
	_, data, err := conn.Read(ctx)
	if err != nil {
		return "", "", err
	}
	var payload tvEvent
	if err := json.Unmarshal(data, &payload); err != nil {
		return "", "", err
	}
	return payload.Event, strings.TrimSpace(payload.Data.Token), nil
}
