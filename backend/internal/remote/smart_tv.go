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
	"sort"
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
	// client validates reachability but not the certificate chain.
	transport := &http.Transport{
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: true},
		ResponseHeaderTimeout: 10 * time.Second,
		DialContext:           (&net.Dialer{Timeout: 6 * time.Second}).DialContext,
	}
	return &SmartTVDriver{
		applicationName: "Valkyris",
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
	writeCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	if err := item.conn.Write(writeCtx, websocket.MessageText, payload); err != nil {
		return fmt.Errorf("não foi possível enviar o comando: %w", err)
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
	handshake, cancel := context.WithTimeout(ctx, 6*time.Second)
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

// Apps lists the applications installed on the TV. Two strategies are raced:
// the WebSocket installed-app event (older firmwares answer with the full list)
// and a per-ID lookup against the TV REST API (newer firmwares dropped the
// event, so we ask the TV which known apps it actually has). The WebSocket
// result wins when it arrives because it is complete.
func (d *SmartTVDriver) Apps(ctx context.Context, device Device, token string) ([]App, error) {
	type result struct {
		apps []App
		err  error
	}
	wsCtx, wsCancel := context.WithCancel(ctx)
	defer wsCancel()
	wsCh := make(chan result, 1)
	go func() {
		conn, err := d.connectReady(wsCtx, device, token)
		if err != nil {
			wsCh <- result{nil, err}
			return
		}
		apps, requestErr := d.requestApps(wsCtx, conn)
		conn.Close(websocket.StatusNormalClosure, "")
		wsCh <- result{apps, requestErr}
	}()
	restCh := make(chan result, 1)
	go func() {
		apps, err := d.appsViaRest(ctx, device)
		restCh <- result{apps, err}
	}()

	var ws result
	select {
	case ws = <-wsCh:
	case <-time.After(3 * time.Second):
		// This firmware does not answer the installed-app event; stop waiting.
	}
	wsCancel()
	rest := <-restCh
	merged := mergeApps(ws.apps, rest.apps)
	if len(merged) > 0 {
		return merged, nil
	}
	if ws.err != nil {
		return nil, ws.err
	}
	return []App{}, nil
}

// mergeApps unions app lists, dropping duplicates by name, and returns a stable
// alphabetical order.
func mergeApps(groups ...[]App) []App {
	out := make([]App, 0)
	seen := map[string]struct{}{}
	for _, group := range groups {
		for _, app := range group {
			key := strings.ToLower(strings.TrimSpace(app.Name))
			if key == "" {
				continue
			}
			if _, ok := seen[key]; ok {
				continue
			}
			seen[key] = struct{}{}
			out = append(out, app)
		}
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func (d *SmartTVDriver) requestApps(ctx context.Context, conn *websocket.Conn) ([]App, error) {
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.channel.emit",
		"params": map[string]any{"event": "ed.installedApp.get", "to": "host"},
	})
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		return nil, fmt.Errorf("não foi possível pedir a lista de apps: %w", err)
	}
	deadline, cancel := context.WithTimeout(ctx, 6*time.Second)
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

// appsViaRest asks the TV, per known application ID, whether the app is
// installed. The REST endpoint returns the application name only for apps the
// TV actually has (404 otherwise), so the result lists real installed apps.
func (d *SmartTVDriver) appsViaRest(ctx context.Context, device Device) ([]App, error) {
	base := fmt.Sprintf("http://%s/api/v2/applications/", net.JoinHostPort(device.Host, strconv.Itoa(LegacyPort)))
	probeCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	results := make(chan App, len(knownAppIDs))
	sem := make(chan struct{}, 16)
	var wg sync.WaitGroup
	for _, id := range knownAppIDs {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-probeCtx.Done():
				return
			}
			defer func() { <-sem }()
			reqCtx, reqCancel := context.WithTimeout(probeCtx, 2500*time.Millisecond)
			defer reqCancel()
			req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, base+id, nil)
			if err != nil {
				return
			}
			req.Header.Set("Accept", "application/json")
			resp, err := d.client.Do(req)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode/100 != 2 {
				return
			}
			var info struct {
				ID   string `json:"id"`
				Name string `json:"name"`
			}
			if json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&info) != nil || info.Name == "" {
				return
			}
			resolved := info.ID
			if resolved == "" {
				resolved = id
			}
			results <- App{ID: resolved, Name: info.Name}
		}(id)
	}
	wg.Wait()
	close(results)
	out := make([]App, 0, len(knownAppIDs))
	seen := map[string]struct{}{}
	for app := range results {
		key := strings.ToLower(strings.TrimSpace(app.Name))
		if key == "" {
			continue
		}
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, app)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out, nil
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
	seen := map[string]struct{}{}
	for _, item := range items {
		id := item.AppID
		if id == "" {
			id = item.ID
		}
		if id == "" {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		name := item.Name
		if name == "" {
			name = id
		}
		out = append(out, App{ID: id, Name: name})
	}
	return out
}

// Launch starts an installed application on the TV using the kept-alive channel.
func (d *SmartTVDriver) Launch(ctx context.Context, device Device, token, appID string) error {
	item, err := d.ready(ctx, device, token)
	if err != nil {
		return err
	}
	payload, _ := json.Marshal(map[string]any{
		"method": "ms.channel.emit",
		"params": map[string]any{
			"event": "ed.apps.launch",
			"to":    "host",
			"data":  map[string]any{"appId": appID, "action_type": "NATIVE_LAUNCH"},
		},
	})
	writeCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	if err := item.conn.Write(writeCtx, websocket.MessageText, payload); err != nil {
		d.drop(item)
		return fmt.Errorf("não foi possível abrir o app: %w", err)
	}
	return nil
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
