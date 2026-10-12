package tuya

import (
	"bytes"
	"crypto/aes"
	"encoding/hex"
	"strings"
	"testing"
	"time"
)

const (
	testDeviceID = "TESTID1234567890ABCD"
	testLocalKey = "0123456789abcdef"
)

var fixedClock = time.Unix(1786340629, 0)

// The wire format is checked against frames captured from tinytuya, the
// reference implementation, so a regression in framing, encryption or the JSON
// envelope is caught without a physical device.
const (
	vectorV31Control = "000055aa00000001000000070000009b332e313133326633336265313331616437653939364e775a41494f49496a5248516f55785a39382b5048334b623046344b57437551554450412f4c31627a2b51397944306334395667564f692f484f44515163494f3252617964507550644e7279314a444b367072536377554e306a4d4475686d6b47375574454e735339636b626c4e5264546d626c49756b382b4d5372482b919e34800000aa55"
	vectorV31Query   = "000055aa000000010000000a000000747b2267774964223a225445535449443132333435363738393041424344222c226465764964223a225445535449443132333435363738393041424344222c22756964223a225445535449443132333435363738393041424344222c2274223a2231373836333430363239227da834ed100000aa55"
	vectorV33Query   = "000055aa000000010000000a00000078c579daf486bec3faf3d5ed0d9a71f78823a457bae7c691dace370949cc64b02e6439127224656ff0ef2c974a48d0b83f7b25fa24bceaa8b91a8bae520a5cdf14f1e0e205443cfb7b3a9e64a57629fbf38b7a8be9e0a5c89b82bcc5ebe0c18b7f3601fa95b93378536cb2dcce5a15c824d0ac21ca0000aa55"
)

func TestPackMatchesCapturedV31ControlFrame(t *testing.T) {
	want, err := hex.DecodeString(vectorV31Control)
	if err != nil {
		t.Fatal(err)
	}
	body := []byte(`{"devId":"TESTID1234567890ABCD","uid":"TESTID1234567890ABCD","t":"1786340629","dps":{"1":true}}`)
	payload, err := encryptPayload(3.1, CmdControl, []byte(testLocalKey), body)
	if err != nil {
		t.Fatal(err)
	}
	got, err := packFrame(1, CmdControl, payload, 3.1, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("3.1 control frame mismatch\n got: %s\nwant: %s", hex.EncodeToString(got), hex.EncodeToString(want))
	}
}

func TestPackMatchesCapturedV31QueryFrame(t *testing.T) {
	want, _ := hex.DecodeString(vectorV31Query)
	body := []byte(`{"gwId":"TESTID1234567890ABCD","devId":"TESTID1234567890ABCD","uid":"TESTID1234567890ABCD","t":"1786340629"}`)
	payload, err := encryptPayload(3.1, CmdDPQuery, []byte(testLocalKey), body)
	if err != nil {
		t.Fatal(err)
	}
	got, err := packFrame(1, CmdDPQuery, payload, 3.1, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("3.1 query frame mismatch\n got: %s\nwant: %s", hex.EncodeToString(got), hex.EncodeToString(want))
	}
}

func TestPackMatchesCapturedV33QueryFrame(t *testing.T) {
	want, _ := hex.DecodeString(vectorV33Query)
	body := []byte(`{"gwId":"TESTID1234567890ABCD","devId":"TESTID1234567890ABCD","uid":"TESTID1234567890ABCD","t":"1786340629"}`)
	payload, err := encryptPayload(3.3, CmdDPQuery, []byte(testLocalKey), body)
	if err != nil {
		t.Fatal(err)
	}
	got, err := packFrame(1, CmdDPQuery, payload, 3.3, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("3.3 query frame mismatch\n got: %s\nwant: %s", hex.EncodeToString(got), hex.EncodeToString(want))
	}
}

func TestUnpackRoundTripEveryVersion(t *testing.T) {
	body := []byte(`{"dps":{"20":true,"22":500,"23":250,"24":"00f003e803e8"}}`)
	for _, version := range []float64{3.1, 3.3, 3.4, 3.5} {
		command := CmdControlNew
		if version < 3.3 {
			command = CmdControl
		}
		payload, err := encryptPayload(version, command, []byte(testLocalKey), body)
		if err != nil {
			t.Fatalf("version %.1f: encrypt: %v", version, err)
		}
		frame, err := packFrame(7, command, payload, version, []byte(testLocalKey))
		if err != nil {
			t.Fatalf("version %.1f: pack: %v", version, err)
		}
		length, err := frameLength(frame)
		if err != nil {
			t.Fatalf("version %.1f: length: %v", version, err)
		}
		if length != len(frame) {
			t.Fatalf("version %.1f: frameLength reported %d, frame has %d", version, length, len(frame))
		}
		message, err := unpackFrame(frame, version, []byte(testLocalKey))
		if err != nil {
			t.Fatalf("version %.1f: unpack: %v", version, err)
		}
		plain, err := decryptPayload(version, []byte(testLocalKey), message.payload)
		if err != nil {
			t.Fatalf("version %.1f: decrypt: %v", version, err)
		}
		if !strings.Contains(string(plain), `"dps"`) {
			t.Fatalf("version %.1f: unexpected plaintext %q", version, plain)
		}
	}
}

func TestUnpackRejectsATamperedFrame(t *testing.T) {
	payload, err := encryptPayload(3.3, CmdDPQuery, []byte(testLocalKey), []byte(`{"dps":{"20":true}}`))
	if err != nil {
		t.Fatal(err)
	}
	frame, err := packFrame(3, CmdDPQuery, payload, 3.3, nil)
	if err != nil {
		t.Fatal(err)
	}
	frame[len(frame)-6] ^= 0xff
	if _, err := unpackFrame(frame, 3.3, nil); err == nil {
		t.Fatal("a tampered frame was accepted")
	}
}

func TestUnpackRejectsAWrongKeyForV34(t *testing.T) {
	payload, err := encryptPayload(3.4, CmdDPQuery, []byte(testLocalKey), []byte(`{"dps":{"20":true}}`))
	if err != nil {
		t.Fatal(err)
	}
	// The HMAC uses the local key, so a frame sealed with another key is rejected
	// before the payload is even decrypted.
	frame, err := packFrame(4, CmdDPQuery, payload, 3.4, []byte(testLocalKey))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := unpackFrame(frame, 3.4, []byte("ffffffffffffffff")); err == nil {
		t.Fatal("a frame with a foreign key was accepted")
	}
}

func TestKeyLengthIsEnforced(t *testing.T) {
	if _, err := encryptPayload(3.3, CmdDPQuery, []byte("short"), []byte("{}")); err == nil {
		t.Fatal("a short key was accepted")
	}
}

func TestDecryptPayloadReadsV31Answers(t *testing.T) {
	// A device answers a v3.1 command with the same envelope it received.
	body := []byte(`{"devId":"TESTID1234567890ABCD","dps":{"1":true,"2":"white","3":200}}`)
	encoded, err := encryptPayload(3.1, CmdControl, []byte(testLocalKey), body)
	if err != nil {
		t.Fatal(err)
	}
	plain, err := decryptPayload(3.1, []byte(testLocalKey), encoded)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(body, plain) {
		t.Fatalf("v3.1 answer round trip mismatch: %s", plain)
	}
}

// The UDP discovery key is public knowledge: it protects the announcement, not
// any account.
func TestDiscoveryPayloadUsesThePublicUDPKey(t *testing.T) {
	body, err := jsonMarshalAppBroadcast("192.168.1.10")
	if err != nil {
		t.Fatal(err)
	}
	frame, err := packFrame(0, CmdRequestDevInfo, body, 3.5, udpKey[:])
	if err != nil {
		t.Fatal(err)
	}
	if !isPrefix(frame, prefix6699) {
		t.Fatalf("discovery frame does not use the 6699 prefix: %x", frame[:4])
	}
	plain, err := decodeUDPPayload(frame)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(plain), `"from":"app"`) {
		t.Fatalf("unexpected discovery payload: %s", plain)
	}
}

func TestNormalizeVersion(t *testing.T) {
	cases := map[float64]float64{0: 3.3, 3.1: 3.1, 3.2: 3.3, 3.3: 3.3, 3.4: 3.4, 3.5: 3.5}
	for input, want := range cases {
		got, err := normalizeVersion(input)
		if err != nil {
			t.Fatalf("normalizeVersion(%.1f): %v", input, err)
		}
		if got != want {
			t.Fatalf("normalizeVersion(%.1f) = %.1f, want %.1f", input, got, want)
		}
	}
	if _, err := normalizeVersion(4.2); err == nil {
		t.Fatal("an unsupported version was accepted")
	}
}

func TestUnpadAcceptsZeroPadding(t *testing.T) {
	// Some firmwares pad with zeros instead of PKCS#7.
	block, err := aes.NewCipher([]byte(testLocalKey))
	if err != nil {
		t.Fatal(err)
	}
	plain := padToBlockSize([]byte(`{"dps":{}}`), 0)
	encrypted := make([]byte, len(plain))
	for i := 0; i < len(plain); i += aes.BlockSize {
		block.Encrypt(encrypted[i:i+aes.BlockSize], plain[i:i+aes.BlockSize])
	}
	decoded, err := ecbDecrypt([]byte(testLocalKey), encrypted)
	if err != nil {
		t.Fatal(err)
	}
	if string(decoded) != `{"dps":{}}` {
		t.Fatalf("zero padded payload decoded as %q", decoded)
	}
}

func TestUnpadAcceptsPKCS7Padding(t *testing.T) {
	encrypted, err := ecbEncrypt([]byte(testLocalKey), []byte(`{"dps":{"20":true}}`))
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := ecbDecrypt([]byte(testLocalKey), encrypted)
	if err != nil {
		t.Fatal(err)
	}
	if string(decoded) != `{"dps":{"20":true}}` {
		t.Fatalf("PKCS#7 payload decoded as %q", decoded)
	}
}

func padToBlockSize(raw []byte, filler byte) []byte {
	out := append([]byte{}, raw...)
	for len(out)%aes.BlockSize != 0 {
		out = append(out, filler)
	}
	return out
}
