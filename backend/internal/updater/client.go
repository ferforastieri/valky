// Package updater talks to the host-side updater over a local Unix socket.
// The backend never gets Docker access: it can only ask the updater to run the
// official installer, with the token provisioned by install.sh.
package updater

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

var ErrBusy = errors.New("an update is already running")

type Status struct {
	State      string    `json:"state"`
	StartedAt  time.Time `json:"startedAt,omitempty"`
	FinishedAt time.Time `json:"finishedAt,omitempty"`
	Error      string    `json:"error,omitempty"`
	Output     string    `json:"output,omitempty"`
}

type Client struct {
	socket string
	token  string
	http   *http.Client
}

func New(socket, token string) *Client {
	socket = strings.TrimSpace(socket)
	token = strings.TrimSpace(token)
	client := &Client{socket: socket, token: token}
	if socket == "" {
		return client
	}
	client.http = &http.Client{
		Timeout: 15 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				dialer := net.Dialer{Timeout: 5 * time.Second}
				return dialer.DialContext(ctx, "unix", socket)
			},
		},
	}
	return client
}

func (c *Client) Enabled() bool { return c != nil && c.socket != "" && c.token != "" && c.http != nil }

func (c *Client) Start(ctx context.Context) (Status, error) {
	return c.request(ctx, http.MethodPost, "/update")
}

func (c *Client) Status(ctx context.Context) (Status, error) {
	return c.request(ctx, http.MethodGet, "/status")
}

func (c *Client) request(ctx context.Context, method, path string) (Status, error) {
	if !c.Enabled() {
		return Status{}, errors.New("updater não configurado")
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://unix"+path, nil)
	if err != nil {
		return Status{}, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	res, err := c.http.Do(req)
	if err != nil {
		return Status{}, fmt.Errorf("falar com o updater: %w", err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 64<<10))
	if res.StatusCode == http.StatusConflict {
		return Status{}, ErrBusy
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		var payload struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(body, &payload)
		message := strings.TrimSpace(payload.Error)
		if message == "" {
			message = strings.TrimSpace(string(body))
		}
		return Status{}, fmt.Errorf("updater respondeu %s: %s", res.Status, message)
	}
	var status Status
	if err := json.Unmarshal(body, &status); err != nil {
		return Status{}, fmt.Errorf("ler o updater: %w", err)
	}
	return status, nil
}
