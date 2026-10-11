package remote

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/coder/websocket"
)

// SamsungDriver speaks the Tizen remote-control WebSocket channel used by
// 2016+ Samsung TVs. It connects to
// wss://HOST:8002/api/v2/channels/samsung.remote.control, falls back to the
// legacy ws://HOST:8001 channel, and exchanges the small JSON envelope the TV
// expects. The TV shows an on-screen prompt the first time a controller pairs;
// the token it returns afterwards authorizes later key presses without prompts.
type SamsungDriver struct {
	applicationName string
	client          *http.Client
}

func NewSamsungDriver() *SamsungDriver {
	// Samsung TVs present a self-signed certificate on the secure channel, so
	// the client validates reachability but not the certificate chain.
	transport := &http.Transport{
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: true},
		ResponseHeaderTimeout: 10 * time.Second,
		DialContext:           (&net.Dialer{Timeout: 6 * time.Second}).DialContext,
	}
	return &SamsungDriver{
		applicationName: "Valkyris",
		client:          &http.Client{Transport: transport},
	}
}

func (*SamsungDriver) Available() bool { return true }

func (d *SamsungDriver) Pair(ctx context.Context, device Device) (string, error) {
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

func (d *SamsungDriver) Send(ctx context.Context, device Device, token, key string) error {
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

func (d *SamsungDriver) dial(ctx context.Context, device Device, token string) (*websocket.Conn, error) {
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

type samsungEvent struct {
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
	var payload samsungEvent
	if err := json.Unmarshal(data, &payload); err != nil {
		return "", "", err
	}
	return payload.Event, strings.TrimSpace(payload.Data.Token), nil
}
