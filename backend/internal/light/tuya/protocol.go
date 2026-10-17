// Package tuya implements the local Wi-Fi control protocol used by Tuya-based
// smart lighting (protocol versions 3.1 to 3.5). It is intentionally
// self-contained: no cloud service, no vendor account and no third-party
// dependency participates in discovery or control.
//
// The wire format was reverse engineered by the community and is documented by
// the tinytuya (https://github.com/jasonacox/tinytuya) and tuyapi
// (https://github.com/codetheweb/tuyapi) projects, both MIT licensed. This is an
// independent implementation of that documented format, limited to what the
// Valky lighting adapter needs: device discovery and data-point read/write.
package tuya

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"hash/crc32"
)

// Frame prefixes and suffixes. The prefix is transmitted big-endian, so the
// first bytes on the wire are 00 00 55 AA (or 00 00 66 99 for GCM frames).
const (
	prefix55AA uint32 = 0x000055AA
	prefix6699 uint32 = 0x00006699
	suffix55AA uint32 = 0x0000AA55
	suffix6699 uint32 = 0x00009966
)

// Commands used by the adapter.
const (
	CmdSessionKeyStart  uint32 = 0x03 // negotiate a session key (3.4/3.5 login)
	CmdControl          uint32 = 0x07 // write data points
	CmdHeartbeat        uint32 = 0x09 // keep-alive
	CmdDPQuery          uint32 = 0x0a // read data points
	CmdControlNew       uint32 = 0x0d // write data points (3.3+ envelope)
	CmdDPQueryNew       uint32 = 0x10 // read data points (3.3+ envelope)
	CmdUpdatedps        uint32 = 0x12 // request a refresh of data points
	CmdRequestDevInfo   uint32 = 0x25 // broadcast to port 7000 to wake v3.5 devices
	CmdLanExternalData  uint32 = 0x40 // extended LAN stream
	CmdStatusReport     uint32 = 0x08 // unsolicited state report from the device
	CmdHeartbeatResp    uint32 = 0x09 // heartbeat response (same code, retcode 0)
	CmdSessionKeyResp   uint32 = 0x04 // session key negotiation response
	CmdSessionKeyFinish uint32 = 0x05 // session key negotiation finish
)

// Network constants shared with the vendor application.
const (
	PortTCP     = 6668 // local control channel
	PortUDP31   = 6666 // discovery for protocol 3.1
	PortUDP33   = 6667 // encrypted discovery for protocol 3.3/3.4
	PortUDPApp  = 7000 // application channel, used to wake 3.5 devices
	MaxPayload  = 1440 // the devices drop larger frames
	DefaultPort = PortTCP
)

// udpKey is the fixed key the vendor application uses for UDP discovery
// payloads. It is public knowledge (part of the app, not of any account).
var udpKey = md5.Sum([]byte("yGAdlopoPVldABfn"))

// Errors reported by this package.
var (
	ErrShortFrame    = errors.New("tuya: frame too short")
	ErrBadPrefix     = errors.New("tuya: unexpected frame prefix")
	ErrBadSuffix     = errors.New("tuya: unexpected frame suffix")
	ErrBadCRC        = errors.New("tuya: frame integrity check failed")
	ErrBadPayload    = errors.New("tuya: payload size out of range")
	ErrBadKey        = errors.New("tuya: the local key must be 16 characters")
	ErrBadVersion    = errors.New("tuya: unsupported protocol version")
	ErrDeviceReject  = errors.New("tuya: the device rejected the request")
	ErrNoResponse    = errors.New("tuya: no response from the device")
	ErrNotEncrypted  = errors.New("tuya: unexpected plaintext payload")
	ErrSessionNeeded = errors.New("tuya: the device requires the 3.4/3.5 login handshake")
)

// Commands that never carry the version header inside the encrypted payload.
var noVersionHeader = map[uint32]bool{
	CmdDPQuery:          true,
	CmdDPQueryNew:       true,
	CmdUpdatedps:        true,
	CmdHeartbeat:        true,
	CmdSessionKeyStart:  true,
	CmdSessionKeyResp:   true,
	CmdSessionKeyFinish: true,
	CmdLanExternalData:  true,
	CmdRequestDevInfo:   true,
}

// ---------------------------------------------------------------------------
// Crypto helpers
// ---------------------------------------------------------------------------

func padPKCS7(raw []byte) []byte {
	padding := aes.BlockSize - len(raw)%aes.BlockSize
	out := make([]byte, len(raw)+padding)
	copy(out, raw)
	for i := len(raw); i < len(out); i++ {
		out[i] = byte(padding)
	}
	return out
}

// unpadPKCS7 removes PKCS#7 padding. Some firmwares answer with zero padding
// instead, so a full-block of zeros and a plain trailing zero are both
// tolerated before the raw payload is handed over to the JSON decoder.
func unpadPKCS7(raw []byte) []byte {
	if len(raw) == 0 {
		return raw
	}
	if padding := int(raw[len(raw)-1]); padding >= 1 && padding <= aes.BlockSize && padding <= len(raw) {
		valid := true
		for i := len(raw) - padding; i < len(raw); i++ {
			if raw[i] != byte(padding) {
				valid = false
				break
			}
		}
		if valid {
			return raw[:len(raw)-padding]
		}
	}
	return bytes.TrimRight(raw, "\x00")
}

func ecbEncrypt(key, raw []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	padded := padPKCS7(raw)
	out := make([]byte, len(padded))
	for i := 0; i < len(padded); i += aes.BlockSize {
		block.Encrypt(out[i:i+aes.BlockSize], padded[i:i+aes.BlockSize])
	}
	return out, nil
}

func ecbDecrypt(key, enc []byte) ([]byte, error) {
	if len(enc) == 0 || len(enc)%aes.BlockSize != 0 {
		return nil, ErrBadPayload
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	out := make([]byte, len(enc))
	for i := 0; i < len(enc); i += aes.BlockSize {
		block.Decrypt(out[i:i+aes.BlockSize], enc[i:i+aes.BlockSize])
	}
	return unpadPKCS7(out), nil
}

func gcmSeal(key, nonce, raw, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return aead.Seal(nil, nonce, raw, aad), nil
}

func gcmOpen(key, nonce, sealed, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return aead.Open(nil, nonce, sealed, aad)
}

// hmacSHA256 is the keyed digest the device uses to prove it holds the same
// local key during the 3.5 session handshake.
func hmacSHA256(key, message []byte) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write(message)
	return mac.Sum(nil)
}

// ---------------------------------------------------------------------------
// Protocol version handling
// ---------------------------------------------------------------------------

func normalizeVersion(version float64) (float64, error) {
	switch {
	case version <= 0:
		return 3.3, nil
	case version >= 3.05 && version < 3.15:
		return 3.1, nil
	case version >= 3.15 && version < 3.35:
		return 3.3, nil
	case version >= 3.35 && version < 3.45:
		return 3.4, nil
	case version >= 3.45 && version <= 3.5:
		return 3.5, nil
	}
	return 0, fmt.Errorf("%w: %.1f", ErrBadVersion, version)
}

// versionBytes is the three character tag ("3.1", "3.3", ...) that firmwares
// expect in front of an encrypted payload.
func versionBytes(version float64) []byte {
	switch {
	case version < 3.2:
		return []byte("3.1")
	case version < 3.4:
		return []byte("3.3")
	case version < 3.5:
		return []byte("3.4")
	default:
		return []byte("3.5")
	}
}

// versionHeader is the 15 byte header prepended to payloads that are not in
// noVersionHeader: the version tag followed by twelve zero bytes.
func versionHeader(version float64) []byte {
	header := make([]byte, 0, 15)
	header = append(header, versionBytes(version)...)
	return append(header, make([]byte, 12)...)
}

// ---------------------------------------------------------------------------
// Frame packing
// ---------------------------------------------------------------------------

// packFrame builds a complete frame. The body must already carry the payload as
// it goes on the wire (encrypted when the protocol version requires it).
//
// Both frame families end with a terminator: the classic 55AA family uses a
// 4 byte CRC32 (HMAC-SHA256 for 3.4+) followed by 00 00 AA 55, while the 3.5
// family uses AES-GCM and the 00 00 99 66 suffix.
func packFrame(seq, cmd uint32, body []byte, version float64, key []byte) ([]byte, error) {
	if len(body) > MaxPayload {
		return nil, ErrBadPayload
	}
	if version >= 3.5 {
		if len(key) != aes.BlockSize {
			return nil, ErrBadKey
		}
		header := make([]byte, 18)
		binary.BigEndian.PutUint32(header[0:4], prefix6699)
		binary.BigEndian.PutUint32(header[6:10], seq)
		binary.BigEndian.PutUint32(header[10:14], cmd)
		// length = nonce (12) + ciphertext + tag (16); the 4 byte suffix is not
		// counted (it is added on top when framing).
		binary.BigEndian.PutUint32(header[14:18], uint32(len(body)+28))
		nonce := make([]byte, 12)
		if _, err := rand.Read(nonce); err != nil {
			return nil, err
		}
		sealed, err := gcmSeal(key, nonce, body, header[4:18])
		if err != nil {
			return nil, err
		}
		frame := append(header, nonce...)
		frame = append(frame, sealed...)
		suffix := make([]byte, 4)
		binary.BigEndian.PutUint32(suffix, suffix6699)
		return append(frame, suffix...), nil
	}

	header := make([]byte, 16)
	binary.BigEndian.PutUint32(header[0:4], prefix55AA)
	binary.BigEndian.PutUint32(header[4:8], seq)
	binary.BigEndian.PutUint32(header[8:12], cmd)

	trailer := 8
	if version >= 3.4 {
		if len(key) != aes.BlockSize {
			return nil, ErrBadKey
		}
		trailer = 36 // HMAC-SHA256 (32) + suffix (4)
	}
	binary.BigEndian.PutUint32(header[12:16], uint32(len(body)+trailer))

	frame := append(header, body...)
	if version >= 3.4 {
		mac := hmac.New(sha256.New, key)
		mac.Write(frame)
		frame = append(frame, mac.Sum(nil)...)
	} else {
		sum := make([]byte, 4)
		binary.BigEndian.PutUint32(sum, crc32.ChecksumIEEE(frame))
		frame = append(frame, sum...)
	}
	suffix := make([]byte, 4)
	binary.BigEndian.PutUint32(suffix, suffix55AA)
	return append(frame, suffix...), nil
}

// frame is a decoded message.
type frame struct {
	seq     uint32
	cmd     uint32
	payload []byte
	secure  bool // true when the payload was protected (GCM/HMAC)
}

// unpackFrame decodes one complete frame. It returns an error when the buffer
// does not yet hold a full frame; callers use frameLength to know how much data
// to read.
func unpackFrame(data []byte, version float64, key []byte) (frame, error) {
	if len(data) < 16 {
		return frame{}, ErrShortFrame
	}
	prefix := binary.BigEndian.Uint32(data[0:4])
	switch prefix {
	case prefix6699:
		if len(data) < 18 {
			return frame{}, ErrShortFrame
		}
		if len(key) != aes.BlockSize {
			return frame{}, ErrBadKey
		}
		length := int(binary.BigEndian.Uint32(data[14:18]))
		total := 18 + length + 4
		if length < 28 || length > MaxPayload+64 {
			return frame{}, ErrBadPayload
		}
		if len(data) < total {
			return frame{}, ErrShortFrame
		}
		suffix := binary.BigEndian.Uint32(data[total-4 : total])
		if suffix != suffix6699 {
			return frame{}, ErrBadSuffix
		}
		sealed := data[18 : total-4]
		if len(sealed) < 12+16 {
			return frame{}, ErrShortFrame
		}
		plain, err := gcmOpen(key, sealed[:12], sealed[12:], data[4:18])
		if err != nil {
			return frame{}, fmt.Errorf("%w: %v", ErrBadCRC, err)
		}
		return frame{
			seq:     binary.BigEndian.Uint32(data[6:10]),
			cmd:     binary.BigEndian.Uint32(data[10:14]),
			payload: plain,
			secure:  true,
		}, nil
	case prefix55AA:
		length := int(binary.BigEndian.Uint32(data[12:16]))
		trailer := 8
		if version >= 3.4 {
			trailer = 36
		}
		if length < 0 || length > MaxPayload+64 {
			return frame{}, ErrBadPayload
		}
		total := 16 + length
		if len(data) < total {
			return frame{}, ErrShortFrame
		}
		if binary.BigEndian.Uint32(data[total-4:total]) != suffix55AA {
			return frame{}, ErrBadSuffix
		}
		body := data[16 : total-trailer]
		carried := data[total-trailer : total-4]
		if version >= 3.4 {
			if len(key) != aes.BlockSize {
				return frame{}, ErrBadKey
			}
			mac := hmac.New(sha256.New, key)
			mac.Write(data[:total-trailer])
			if !hmac.Equal(mac.Sum(nil), carried) {
				return frame{}, ErrBadCRC
			}
		} else {
			sum := make([]byte, 4)
			binary.BigEndian.PutUint32(sum, crc32.ChecksumIEEE(data[:total-trailer]))
			if !hmac.Equal(sum, carried) {
				return frame{}, ErrBadCRC
			}
		}
		return frame{
			seq:     binary.BigEndian.Uint32(data[4:8]),
			cmd:     binary.BigEndian.Uint32(data[8:12]),
			payload: body,
		}, nil
	}
	return frame{}, ErrBadPrefix
}

// frameLength reports the total size of the frame starting at data, or 0 when
// the header is not complete yet.
func frameLength(data []byte) (int, error) {
	if len(data) < 16 {
		return 0, ErrShortFrame
	}
	switch binary.BigEndian.Uint32(data[0:4]) {
	case prefix55AA:
		length := int(binary.BigEndian.Uint32(data[12:16]))
		if length > MaxPayload+64 {
			return 0, ErrBadPayload
		}
		return 16 + length, nil
	case prefix6699:
		if len(data) < 18 {
			return 0, ErrShortFrame
		}
		length := int(binary.BigEndian.Uint32(data[14:18]))
		if length > MaxPayload+64 {
			return 0, ErrBadPayload
		}
		return 18 + length + 4, nil
	}
	return 0, ErrBadPrefix
}

// ---------------------------------------------------------------------------
// Payload helpers
// ---------------------------------------------------------------------------

// encryptPayload prepares a command payload for the wire format of version.
func encryptPayload(version float64, cmd uint32, key, raw []byte) ([]byte, error) {
	if len(key) != aes.BlockSize {
		return nil, ErrBadKey
	}
	switch {
	case version >= 3.5:
		// 3.5 wraps the version header inside the GCM payload.
		if !noVersionHeader[cmd] {
			raw = append(versionHeader(version), raw...)
		}
		return raw, nil
	case version >= 3.4:
		if !noVersionHeader[cmd] {
			raw = append(versionHeader(version), raw...)
		}
		return ecbEncrypt(key, raw)
	case version >= 3.2:
		enc, err := ecbEncrypt(key, raw)
		if err != nil {
			return nil, err
		}
		if noVersionHeader[cmd] {
			return enc, nil
		}
		return append(versionHeader(version), enc...), nil
	default:
		// 3.1 has one specific envelope for commands:
		//   3.1 + md5("data="+base64(cipher)+"||lpv=3.1||"+key)[8:24] + base64(cipher)
		if cmd != CmdControl && cmd != CmdControlNew {
			return raw, nil
		}
		enc, err := ecbEncrypt(key, raw)
		if err != nil {
			return nil, err
		}
		encoded := []byte(base64Encode(enc))
		sum := md5.Sum(append(append(append([]byte("data="), encoded...), []byte("||lpv=3.1||")...), key...))
		digest := hex.EncodeToString(sum[:])[8:24]
		out := append([]byte("3.1"), []byte(digest)...)
		return append(out, encoded...), nil
	}
}

// decryptPayload turns a received payload back into JSON bytes. Devices answer
// in slightly different shapes: some echo the version header, some prepend a
// four byte result code, and the newest generation leaves the JSON in the clear
// because the frame itself was sealed.
func decryptPayload(version float64, key, payload []byte) ([]byte, error) {
	if len(payload) == 0 {
		return nil, ErrBadPayload
	}
	header := versionHeader(version)
	tag := versionBytes(version)
	stripHeader := func(raw []byte) []byte {
		switch {
		case bytes.HasPrefix(raw, header):
			return raw[len(header):]
		case bytes.HasPrefix(raw, tag):
			return raw[len(tag):]
		case len(raw) >= 4 && raw[0] == 0:
			return raw[4:]
		default:
			return raw
		}
	}
	switch {
	case version >= 3.5:
		return stripHeader(payload), nil
	case version >= 3.4:
		plain, err := ecbDecrypt(key, stripHeader(payload))
		if err != nil {
			return nil, err
		}
		return stripHeader(plain), nil
	case version >= 3.2:
		plain, err := ecbDecrypt(key, stripHeader(payload))
		if err != nil {
			return nil, err
		}
		return stripHeader(plain), nil
	default:
		if bytes.HasPrefix(payload, []byte("3.1")) {
			payload = payload[3:]
			if len(payload) < 16 {
				return nil, ErrBadPayload
			}
			payload = payload[16:]
			enc, err := base64Decode(payload)
			if err != nil {
				return nil, err
			}
			return ecbDecrypt(key, enc)
		}
		if bytes.HasPrefix(payload, []byte("{")) {
			return payload, nil
		}
		return nil, ErrNotEncrypted
	}
}
