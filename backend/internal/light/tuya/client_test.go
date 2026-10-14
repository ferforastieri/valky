package tuya

import (
	"context"
	"crypto/rand"
	"net"
	"strconv"
	"testing"
	"time"
)

// fakeDevice35 answers a single connection the way a 3.5 device does: it
// negotiates a session key, then replies to the status query with a four byte
// result code in front of the JSON body.
func fakeDevice35(t *testing.T, conn net.Conn, realKey []byte) {
	t.Helper()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	buffer := []byte{}
	key := []byte{}
	read := func(active []byte) frame {
		for {
			if n, err := frameLength(buffer); err == nil && len(buffer) >= n {
				message, err := unpackFrame(buffer[:n], 3.5, active)
				if err != nil {
					t.Errorf("device could not unpack a frame: %v", err)
					return frame{}
				}
				buffer = buffer[n:]
				return message
			}
			chunk := make([]byte, 4096)
			n, err := conn.Read(chunk)
			if err != nil {
				t.Errorf("device read: %v", err)
				return frame{}
			}
			buffer = append(buffer, chunk[:n]...)
		}
	}
	write := func(seq uint32, cmd uint32, body []byte, active []byte) {
		framed, err := packFrame(seq, cmd, body, 3.5, active)
		if err != nil {
			t.Errorf("device pack: %v", err)
			return
		}
		if _, err := conn.Write(framed); err != nil {
			t.Errorf("device write: %v", err)
		}
	}

	start := read(realKey)
	if start.cmd != CmdSessionKeyStart || len(start.payload) != 16 {
		t.Errorf("unexpected handshake start: cmd=%d len=%d", start.cmd, len(start.payload))
		return
	}
	localNonce := start.payload
	remoteNonce := make([]byte, 16)
	_, _ = rand.Read(remoteNonce)
	reply := append([]byte{0, 0, 0, 0}, remoteNonce...)
	reply = append(reply, hmacSHA256(realKey, localNonce)...)
	write(1, CmdSessionKeyResp, reply, realKey)

	finish := read(realKey)
	if finish.cmd != CmdSessionKeyFinish {
		t.Errorf("unexpected finish cmd: %d", finish.cmd)
		return
	}
	xored := make([]byte, 16)
	for i := range xored {
		xored[i] = localNonce[i] ^ remoteNonce[i]
	}
	sealed, err := gcmSeal(realKey, localNonce[:12], xored, nil)
	if err != nil {
		t.Errorf("device session seal: %v", err)
		return
	}
	key = sealed[:16]

	query := read(key)
	if query.cmd != CmdDPQueryNew {
		t.Errorf("unexpected query cmd: %d", query.cmd)
		return
	}
	body := append([]byte{0, 0, 0, 0}, []byte(`{"dps":{"20":true,"22":500}}`)...)
	write(2, CmdDPQueryNew, body, key)
}

func TestClientNegotiatesTheSessionKeyAndReadsStatusOn35(t *testing.T) {
	realKey := "a[tGg.FWUp}UNdOL"
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		fakeDevice35(t, conn, []byte(realKey))
	}()

	address, portText, _ := net.SplitHostPort(listener.Addr().String())
	port, _ := strconv.Atoi(portText)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := Dial(ctx, Options{DeviceID: "eb83892f581112f805ytyu", Address: address, Port: port, LocalKey: realKey, Version: 3.5, Timeout: 3 * time.Second})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.Close()
	dps, err := client.Status(ctx)
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if dps["20"] != true {
		t.Fatalf("unexpected dps: %v", dps)
	}
	if raw, ok := dps["22"].(float64); !ok || raw != 500 {
		t.Fatalf("unexpected brightness: %v", dps["22"])
	}
}

func TestDecodeStatusPayloadSkipsTheResultCode(t *testing.T) {
	dps := DecodeStatusPayload([]byte("\x00\x00\x00\x00{\"dps\":{\"20\":true}}"))
	if dps["20"] != true {
		t.Fatalf("result code was not skipped: %v", dps)
	}
}
