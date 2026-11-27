package tuya

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCloudClientQRAndDevices(t *testing.T) {
	const refreshToken = "refresh-token-123"
	var baseURL string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/v1.0/m/life/home-assistant/qrcode/tokens":
			if r.URL.Query().Get("clientid") != SharingClientID || r.URL.Query().Get("schema") != SharingSchema || r.URL.Query().Get("usercode") != "123456" {
				t.Errorf("parâmetros do QR inesperados: %s", r.URL.RawQuery)
			}
			writeCloudJSON(t, w, map[string]any{"success": true, "result": map[string]string{"qrcode": "qr-token"}})
		case r.Method == http.MethodGet && r.URL.Path == "/v1.0/m/life/home-assistant/qrcode/tokens/qr-token":
			writeCloudJSON(t, w, map[string]any{
				"success": true,
				"t":       123,
				"result": map[string]any{
					"uid":           "user-1",
					"access_token":  "access-token",
					"refresh_token": refreshToken,
					"expire_time":   3600,
					"terminal_id":   "terminal-1",
					"endpoint":      baseURL,
				},
			})
		case r.Method == http.MethodGet && r.URL.Path == "/v1.0/m/life/users/homes":
			secret, queryEnc := cloudTestRequest(t, r, refreshToken)
			if queryEnc != "" {
				t.Errorf("casas não deveriam ter encdata")
			}
			writeEncrypted(t, w, secret, []map[string]string{{"ownerId": "home-1", "name": "Casa"}})
		case r.Method == http.MethodGet && r.URL.Path == "/v1.0/m/life/ha/home/devices":
			secret, queryEnc := cloudTestRequest(t, r, refreshToken)
			var params map[string]string
			plain, err := decryptCloud(secret, queryEnc)
			if err != nil || json.Unmarshal(plain, &params) != nil || params["homeId"] != "home-1" {
				t.Fatalf("homeId não chegou: %q / %v", string(plain), err)
			}
			writeEncrypted(t, w, secret, []map[string]any{{
				"id":           "device-1",
				"name":         "Lâmpada da sala",
				"local_key":    "0123456789abcdef",
				"category":     "dj",
				"product_id":   "product-1",
				"product_name": "Lâmpada",
				"online":       true,
				"ip":           "192.168.15.107",
				"sub":          false,
			}})
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	baseURL = srv.URL

	client := &CloudClient{HTTP: srv.Client(), LoginBaseURL: srv.URL}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	token, err := client.StartQR(ctx, "123456")
	if err != nil || token != "qr-token" {
		t.Fatalf("StartQR = %q, %v", token, err)
	}
	login, authorized, err := client.PollQR(ctx, token, "123456")
	if err != nil || !authorized || login == nil {
		t.Fatalf("PollQR = %#v, %v, %v", login, authorized, err)
	}
	if login.AccessToken != "access-token" || login.Endpoint != srv.URL {
		t.Fatalf("login inesperado: %#v", login)
	}
	devices, err := client.ListDevices(ctx, login)
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].ID != "device-1" || devices[0].LocalKey != "0123456789abcdef" || devices[0].IP != "192.168.15.107" {
		t.Fatalf("dispositivos inesperados: %#v", devices)
	}
}

// cloudTestRequest confere a assinatura no mesmo formato do SDK e devolve o
// segredo da requisição para o teste decifrar o encdata.
func cloudTestRequest(t *testing.T, r *http.Request, refreshToken string) (string, string) {
	t.Helper()
	rid := r.Header.Get("X-requestId")
	if rid == "" || r.Header.Get("X-appKey") != SharingClientID || r.Header.Get("X-token") != "access-token" {
		t.Fatalf("cabeçalhos inesperados: %v", r.Header)
	}
	hashKey := cloudTestMD5(rid + refreshToken)
	secret := cloudTestSecret(rid, hashKey)
	queryEnc := r.URL.Query().Get("encdata")
	var bodyEnc string
	if r.Method != http.MethodGet {
		raw, _ := io.ReadAll(r.Body)
		var body map[string]string
		_ = json.Unmarshal(raw, &body)
		bodyEnc = body["encdata"]
	}
	parts := []string{
		"X-appKey=" + r.Header.Get("X-appKey"),
		"X-requestId=" + rid,
		"X-time=" + r.Header.Get("X-time"),
		"X-token=" + r.Header.Get("X-token"),
	}
	signStr := strings.Join(parts, "||") + queryEnc + bodyEnc
	if got, want := r.Header.Get("X-sign"), cloudTestSign(hashKey, signStr); got != want {
		t.Fatalf("assinatura = %s, esperado %s", got, want)
	}
	return secret, queryEnc
}

func cloudTestSecret(rid, hashKey string) string {
	mac := hmac.New(sha256.New, []byte(rid))
	mac.Write([]byte(hashKey))
	return hex.EncodeToString(mac.Sum(nil))[:16]
}

func cloudTestSign(hashKey, signStr string) string {
	mac := hmac.New(sha256.New, []byte(hashKey))
	mac.Write([]byte(signStr))
	return hex.EncodeToString(mac.Sum(nil))
}

func cloudTestMD5(value string) string {
	sum := md5.Sum([]byte(value))
	return hex.EncodeToString(sum[:])
}

func writeEncrypted(t *testing.T, w http.ResponseWriter, secret string, value any) {
	t.Helper()
	plain, _ := json.Marshal(value)
	encoded, err := cloudTestEncrypt(secret, plain)
	if err != nil {
		t.Fatal(err)
	}
	writeCloudJSON(t, w, map[string]any{"success": true, "result": encoded})
}

func cloudTestEncrypt(secret string, plain []byte) (string, error) {
	block, err := aes.NewCipher([]byte(secret))
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := gcm.Seal(nil, nonce, plain, nil)
	return base64.StdEncoding.EncodeToString(nonce) + base64.StdEncoding.EncodeToString(sealed), nil
}

func writeCloudJSON(t *testing.T, w http.ResponseWriter, value any) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		t.Fatal(err)
	}
}
