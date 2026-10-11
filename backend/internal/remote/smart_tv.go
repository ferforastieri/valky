package remote

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
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
type SmartTVDriver struct {
	applicationName string
	client          *http.Client
}

func NewSmartTVDriver() *SmartTVDriver {
	// These TVs present a self-signed certificate on the secure channel, so the
	// client validates reachability but not the certificate chain.
	transport := &http.Transport{
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: true},
		ResponseHeaderTimeout: 10 * time.Second,
		DialContext:           (&net.Dialer{Timeout: 6 * time.Second}).DialContext,
	}
	return &SmartTVDriver{
		applicationName: "Valkyris",
		client:          &http.Client{Transport: transport},
	}
}

func (*SmartTVDriver) Available() bool { return true }

func (d *SmartTVDriver) Pair(ctx context.Context, device Device) (string, error) {
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
	conn, err := d.dial(ctx, device, token)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	handshake, cancel := context.WithTimeout(ctx, 6*time.Second)
	event, _, readErr := readEvent(handshake, conn)
	cancel()
	if event == "ms.channel.unauthorized" {
		return fmt.Errorf("a TV revogou o token; pareie novamente")
	}
	if readErr != nil {
		return fmt.Errorf("a TV não concluiu a conexão: %w", readErr)
	}
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.remote.control",
		"params": map[string]any{
			"Cmd":          "Click",
			"DataOfCmd":    key,
			"Option":       "false",
			"TypeOfRemote": "SendRemoteKey",
		},
	})
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		return fmt.Errorf("não foi possível enviar o comando: %w", err)
	}
	// Surface a protocol error when the TV rejects the key, without blocking
	// the caller for long when it accepts the command silently.
	ack, ackCancel := context.WithTimeout(ctx, 900*time.Millisecond)
	defer ackCancel()
	if event, _, err := readEvent(ack, conn); err == nil && (event == "ms.error" || event == "ms.channel.unauthorized") {
		return fmt.Errorf("a TV recusou o comando %s", key)
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
		url := remoteURL(attempt.scheme, attempt.addr, d.applicationName, token)
		conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPClient: d.client})
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

// connectReady dials the control channel and consumes the connect handshake,
// returning a connection ready to exchange messages.
func (d *SmartTVDriver) connectReady(ctx context.Context, device Device, token string) (*websocket.Conn, error) {
	conn, err := d.dial(ctx, device, token)
	if err != nil {
		return nil, err
	}
	handshake, cancel := context.WithTimeout(ctx, 6*time.Second)
	event, _, readErr := readEvent(handshake, conn)
	cancel()
	if event == "ms.channel.unauthorized" {
		conn.Close(websocket.StatusNormalClosure, "")
		return nil, fmt.Errorf("a TV revogou o token; pareie novamente")
	}
	if readErr != nil {
		conn.Close(websocket.StatusNormalClosure, "")
		return nil, fmt.Errorf("a TV não concluiu a conexão: %w", readErr)
	}
	return conn, nil
}

// Apps asks the TV for its installed applications over the control channel.
func (d *SmartTVDriver) Apps(ctx context.Context, device Device, token string) ([]App, error) {
	conn, err := d.connectReady(ctx, device, token)
	if err != nil {
		return nil, err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.channel.emit",
		"params": map[string]any{"event": "ed.installedApp.get", "to": "host"},
	})
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		return nil, fmt.Errorf("não foi possível pedir a lista de apps: %w", err)
	}
	deadline, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	for {
		_, data, err := conn.Read(deadline)
		if err != nil {
			return nil, fmt.Errorf("a TV não retornou a lista de apps: %w", err)
		}
		var envelope struct {
			Event string          `json:"event"`
			Data  json.RawMessage `json:"data"`
		}
		if json.Unmarshal(data, &envelope) != nil || envelope.Event != "ed.installedApp.get" {
			continue
		}
		return parseApps(envelope.Data), nil
	}
}

func parseApps(raw json.RawMessage) []App {
	type entry struct {
		AppID string `json:"appId"`
		ID    string `json:"id"`
		Name  string `json:"name"`
	}
	var wrapped struct {
		Data []entry `json:"data"`
	}
	_ = json.Unmarshal(raw, &wrapped)
	items := wrapped.Data
	if len(items) == 0 {
		var direct []entry
		if json.Unmarshal(raw, &direct) == nil {
			items = direct
		}
	}
	out := make([]App, 0, len(items))
	for _, item := range items {
		id := item.AppID
		if id == "" {
			id = item.ID
		}
		if id == "" {
			continue
		}
		name := item.Name
		if name == "" {
			name = id
		}
		out = append(out, App{ID: id, Name: name})
	}
	return out
}

// Launch starts an installed application on the TV.
func (d *SmartTVDriver) Launch(ctx context.Context, device Device, token, appID string) error {
	conn, err := d.connectReady(ctx, device, token)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.channel.emit",
		"params": map[string]any{
			"event": "ed.apps.launch",
			"to":    "host",
			"data":  map[string]any{"appId": appID, "action_type": "NATIVE_LAUNCH"},
		},
	})
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		return fmt.Errorf("não foi possível abrir o app: %w", err)
	}
	return nil
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
			WiFiMAC string `json:"wifiMac"`
			MAC     string `json:"mac"`
		} `json:"device"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&info); err != nil {
		return "", err
	}
	mac := strings.TrimSpace(info.Device.WiFiMAC)
	if mac == "" {
		mac = strings.TrimSpace(info.Device.MAC)
	}
	if mac == "" {
		return "", fmt.Errorf("a TV não informou o MAC")
	}
	return mac, nil
}

// remoteURL builds the channel URL. The application name travels base64
// encoded exactly as the TV expects it in the query string.
func remoteURL(scheme, address, applicationName, token string) string {
	encoded := base64.StdEncoding.EncodeToString([]byte(applicationName))
	url := fmt.Sprintf("%s://%s/api/v2/channels/samsung.remote.control?name=%s", scheme, address, encoded)
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
