package tuya

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

func base64Encode(raw []byte) string { return base64.StdEncoding.EncodeToString(raw) }

func base64Decode(raw []byte) ([]byte, error) { return base64.StdEncoding.DecodeString(string(raw)) }

// Options describe how to reach one device.
type Options struct {
	DeviceID string
	Address  string
	LocalKey string
	Version  float64
	Port     int
	Timeout  time.Duration
	// Now overrides the clock used for the "t" field (tests only).
	Now func() time.Time
}

// Client is a single connection to a device. It is not safe for concurrent
// use: the lighting service serializes commands per device.
type Client struct {
	opts     Options
	version  float64
	conn     net.Conn
	seq      uint32
	buffer   []byte
	readWait time.Duration
	mu       sync.Mutex
	now      func() time.Time

	// realKey is the local key from the pairing. On the 3.5 family the frames
	// use a per-connection session key derived from it, and everything after the
	// handshake must use sessionKey.
	realKey    []byte
	sessionKey []byte
}

// Dial opens the local control channel and validates the handshake by reading
// the device status.
func Dial(ctx context.Context, opts Options) (*Client, error) {
	key := []byte(opts.LocalKey)
	if len(key) != 16 {
		return nil, ErrBadKey
	}
	version, err := normalizeVersion(opts.Version)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(opts.Address) == "" {
		return nil, errors.New("tuya: an address is required")
	}
	if opts.Timeout <= 0 {
		opts.Timeout = 6 * time.Second
	}
	if opts.Port == 0 {
		opts.Port = PortTCP
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	client := &Client{opts: opts, version: version, realKey: key, readWait: opts.Timeout, now: now}
	dialer := net.Dialer{Timeout: opts.Timeout}
	conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(opts.Address, strconv.Itoa(opts.Port)))
	if err != nil {
		return nil, err
	}
	client.conn = conn
	// The 3.5 family refuses the control commands until a session key is
	// negotiated on the connection. Without it the device simply closes the
	// socket (the "EOF" the first attempt saw).
	if version >= 3.5 {
		// The 3.5 family expects the sequence to start at 1 (the vendor app and
		// tinytuya do the same; a 0 here is dropped without an answer).
		client.seq = 1
		if err := client.negotiate(ctx); err != nil {
			_ = conn.Close()
			return nil, err
		}
	}
	return client, nil
}

// key is the key the wire format uses right now: the derated session key after a
// 3.5 handshake, the local key before that (and on every older version).
func (c *Client) key() []byte {
	if c.sessionKey != nil {
		return c.sessionKey
	}
	return c.realKey
}

// negotiate performs the 3.5 session key handshake: a client nonce, the device's
// answer with its nonce and an HMAC, and a final confirmation. The session key is
// the XOR of both nonces wrapped with the local key.
func (c *Client) negotiate(ctx context.Context) error {
	localNonce := make([]byte, 16)
	if _, err := rand.Read(localNonce); err != nil {
		return err
	}
	start, err := encryptPayload(c.version, CmdSessionKeyStart, c.realKey, localNonce)
	if err != nil {
		return err
	}
	if err := c.writeFrame(ctx, c.takeSeq(), CmdSessionKeyStart, start); err != nil {
		return err
	}
	reply, err := c.readFrame(ctx, CmdSessionKeyResp)
	if err != nil {
		return err
	}
	if reply.cmd != CmdSessionKeyResp {
		return ErrSessionNeeded
	}
	// The 3.5 answer carries a 4 byte result code in front of the 16 nonce and
	// the 32 byte HMAC, so the tail is what matters.
	payload := reply.payload
	if len(payload) > 48 {
		payload = payload[len(payload)-48:]
	}
	if len(payload) < 48 {
		return ErrSessionNeeded
	}
	remoteNonce := payload[:16]
	if !hmac.Equal(hmacSHA256(c.realKey, localNonce), payload[16:48]) {
		return ErrBadCRC
	}
	finish, err := encryptPayload(c.version, CmdSessionKeyFinish, c.realKey, hmacSHA256(c.realKey, remoteNonce))
	if err != nil {
		return err
	}
	if err := c.writeFrame(ctx, c.takeSeq(), CmdSessionKeyFinish, finish); err != nil {
		return err
	}
	xored := make([]byte, 16)
	for i := range xored {
		xored[i] = localNonce[i] ^ remoteNonce[i]
	}
	sealed, err := gcmSeal(c.realKey, localNonce[:12], xored, nil)
	if err != nil {
		return err
	}
	c.sessionKey = sealed[:16]
	return nil
}

func (c *Client) takeSeq() uint32 {
	seq := c.seq
	c.seq++
	return seq
}

// Close releases the socket.
func (c *Client) Close() error {
	if c.conn == nil {
		return nil
	}
	return c.conn.Close()
}

// Version reports the protocol version this client speaks.
func (c *Client) Version() float64 { return c.version }

// SetVersion switches the protocol version, used by the version fallback ladder.
func (c *Client) SetVersion(version float64) error {
	normalized, err := normalizeVersion(version)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.version = normalized
	c.buffer = nil
	return nil
}

// Status reads the data points currently reported by the device.
func (c *Client) Status(ctx context.Context) (map[string]any, error) {
	command := CmdDPQuery
	if c.version >= 3.3 {
		command = CmdDPQueryNew
	}
	payload := c.envelope(command, nil)
	response, err := c.exchange(ctx, command, payload)
	if err != nil {
		return nil, err
	}
	dps := response.dataPoints()
	if len(dps) == 0 {
		return nil, ErrNoResponse
	}
	return dps, nil
}

// Set writes data points and returns the state the device reported afterwards.
func (c *Client) Set(ctx context.Context, dps map[string]any) (map[string]any, error) {
	if len(dps) == 0 {
		return nil, errors.New("tuya: no data points to write")
	}
	command := CmdControlNew
	if c.version < 3.3 {
		command = CmdControl
	}
	payload := c.envelope(command, dps)
	response, err := c.exchange(ctx, command, payload)
	if err != nil {
		return nil, err
	}
	if reported := response.dataPoints(); len(reported) > 0 {
		return reported, nil
	}
	// Most firmwares acknowledge a write without echoing the full state, so the
	// caller refreshes explicitly when it needs the complete picture.
	return dps, nil
}

// Heartbeat keeps an idle connection from being closed by the firmware.
func (c *Client) Heartbeat(ctx context.Context) error {
	_, err := c.exchange(ctx, CmdHeartbeat, c.envelope(CmdHeartbeat, nil))
	return err
}

// envelope builds the JSON body of a command. The field order matters: the
// firmwares expect the same order the vendor application sends, so the bodies
// are written by hand instead of through the map encoder.
func (c *Client) envelope(command uint32, dps map[string]any) []byte {
	id := jsonQuote(c.opts.DeviceID)
	timestamp := strconv.FormatInt(c.now().Unix(), 10)
	control := command == CmdControl || command == CmdControlNew
	switch {
	case c.version >= 3.5:
		// On the 3.5 family only the writes carry the protocol 5 envelope;
		// queries and heartbeats go with an empty body.
		if !control {
			return []byte(`{}`)
		}
		inner := `{}`
		if len(dps) > 0 {
			inner = `{"dps":` + encodeDPS(dps) + `}`
		}
		return []byte(`{"protocol":5,"t":` + timestamp + `,"data":` + inner + `}`)
	case c.version >= 3.4:
		inner := `{}`
		if len(dps) > 0 {
			inner = `{"dps":` + encodeDPS(dps) + `}`
		}
		return []byte(`{"protocol":5,"t":` + timestamp + `,"data":` + inner + `}`)
	case control:
		return []byte(`{"devId":` + id + `,"uid":` + id + `,"t":"` + timestamp + `","dps":` + encodeDPS(dps) + `}`)
	default:
		return []byte(`{"gwId":` + id + `,"devId":` + id + `,"uid":` + id + `,"t":"` + timestamp + `"}`)
	}
}

// encodeDPS serializes data points with their identifiers sorted, which keeps
// frames deterministic and reproducible in tests.
func encodeDPS(dps map[string]any) string {
	keys := make([]string, 0, len(dps))
	for key := range dps {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	encoded := make([]byte, 0, 64)
	encoded = append(encoded, '{')
	for index, key := range keys {
		if index > 0 {
			encoded = append(encoded, ',')
		}
		encoded = append(encoded, jsonQuote(key)...)
		encoded = append(encoded, ':')
		value, err := json.Marshal(dps[key])
		if err != nil {
			value = []byte("null")
		}
		encoded = append(encoded, value...)
	}
	return string(append(encoded, '}'))
}

func jsonQuote(value string) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return `""`
	}
	return string(encoded)
}

// exchange sends one command and waits for the matching answer.
func (c *Client) exchange(ctx context.Context, command uint32, body []byte) (frame, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	payload, err := encryptPayload(c.version, command, c.key(), body)
	if err != nil {
		return frame{}, err
	}
	if err := c.writeFrame(ctx, c.takeSeq(), command, payload); err != nil {
		return frame{}, err
	}
	// Devices answer on a fresh sequence number, so the reply is matched by
	// command instead of by sequence.
	for attempt := 0; attempt < 3; attempt++ {
		message, err := c.readFrame(ctx, command)
		if err != nil {
			return frame{}, err
		}
		if message.cmd == CmdStatusReport || message.cmd == CmdHeartbeat {
			continue
		}
		return message, nil
	}
	return frame{}, ErrNoResponse
}

func (c *Client) writeFrame(ctx context.Context, seq, command uint32, payload []byte) error {
	frame, err := packFrame(seq, command, payload, c.version, c.key())
	if err != nil {
		return err
	}
	deadline := time.Now().Add(c.readWait)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := c.conn.SetWriteDeadline(deadline); err != nil {
		return err
	}
	_, err = c.conn.Write(frame)
	return err
}

// readFrame reads until a complete frame arrives. Unsolicited state reports and
// heartbeat answers are filtered by the caller.
func (c *Client) readFrame(ctx context.Context, want uint32) (frame, error) {
	deadline := time.Now().Add(c.readWait)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	for {
		if err := ctx.Err(); err != nil {
			return frame{}, err
		}
		if err := c.conn.SetReadDeadline(deadline); err != nil {
			return frame{}, err
		}
		if length, err := frameLength(c.buffer); err == nil && len(c.buffer) >= length {
			message, err := unpackFrame(c.buffer[:length], c.version, c.key())
			if err != nil {
				return frame{}, err
			}
			c.buffer = c.buffer[length:]
			return message, nil
		}
		chunk := make([]byte, 4096)
		read, err := c.conn.Read(chunk)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				return frame{}, fmt.Errorf("%w: command %d", ErrNoResponse, want)
			}
			return frame{}, err
		}
		c.buffer = append(c.buffer, chunk[:read]...)
	}
}

// frame helpers -----------------------------------------------------------------

type response struct {
	raw map[string]any
}

func (r response) dataPoints() map[string]any {
	if r.raw == nil {
		return nil
	}
	if dps, ok := r.raw["dps"].(map[string]any); ok {
		return dps
	}
	if data, ok := r.raw["data"].(map[string]any); ok {
		if dps, ok := data["dps"].(map[string]any); ok {
			return dps
		}
	}
	return nil
}

func (f frame) dataPoints() map[string]any {
	return decodeResponse(f.payload).dataPoints()
}

func decodeResponse(payload []byte) response {
	text := strings.TrimRight(string(payload), "\x00")
	// Some firmwares — and the whole 3.5 family — put a four byte result code in
	// front of the JSON body, so start at the first brace.
	if index := strings.IndexByte(text, '{'); index > 0 {
		text = text[index:]
	}
	if !strings.HasPrefix(text, "{") {
		return response{}
	}
	var raw map[string]any
	if err := json.Unmarshal([]byte(text), &raw); err != nil {
		return response{}
	}
	return response{raw: raw}
}

// DecodeStatusPayload is exported for tests: it turns a decrypted payload into
// a data-point map.
func DecodeStatusPayload(payload []byte) map[string]any {
	return decodeResponse(payload).dataPoints()
}
