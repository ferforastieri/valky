package tuya

import (
	"bytes"
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
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
)

// The QR flow and the encrypted customer API are the same ones the official
// device-sharing SDK uses (the Home Assistant integration uses this SDK too).
// The client id and schema are public values from that integration: the user
// authorizes a terminal in the Tuya app and the cloud returns the devices of
// the account, including each local key.
const (
	SharingClientID = "HA_3y9q4ak7g4ephrvke"
	SharingSchema   = "haauthorize"
	SharingBaseURL  = "https://apigw.iotbing.com"
	QRContentPrefix = "tuyaSmart--qrLogin?token="
)

// CloudLogin is the terminal authorization returned after the QR is scanned.
type CloudLogin struct {
	UID          string `json:"uid"`
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpireTime   int64  `json:"expire_time"`
	TerminalID   string `json:"terminal_id"`
	Endpoint     string `json:"endpoint"`
	T            int64  `json:"t"`
}

// CloudDevice is the subset of the Tuya device list that the importer needs.
// The local key is a secret and must never be returned by the HTTP API.
type CloudDevice struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	LocalKey    string `json:"local_key"`
	Category    string `json:"category"`
	ProductID   string `json:"product_id"`
	ProductName string `json:"product_name"`
	Online      bool   `json:"online"`
	IP          string `json:"ip"`
	Sub         bool   `json:"sub"`
}

type CloudClient struct {
	HTTP         *http.Client
	LoginBaseURL string
}

func NewCloudClient() *CloudClient {
	return &CloudClient{
		HTTP:         &http.Client{Timeout: 25 * time.Second},
		LoginBaseURL: SharingBaseURL,
	}
}

func (c *CloudClient) client() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return http.DefaultClient
}

func (c *CloudClient) loginBase() string {
	if strings.TrimSpace(c.LoginBaseURL) == "" {
		return SharingBaseURL
	}
	return strings.TrimRight(c.LoginBaseURL, "/")
}

type cloudEnvelope struct {
	Success bool            `json:"success"`
	Result  json.RawMessage `json:"result"`
	Code    json.RawMessage `json:"code"`
	Msg     string          `json:"msg"`
	T       int64           `json:"t"`
}

func (e cloudEnvelope) err(op string) error {
	message := strings.TrimSpace(e.Msg)
	if message == "" {
		message = "a Tuya recusou a operação"
	}
	if len(e.Code) > 0 && string(e.Code) != "null" {
		message = fmt.Sprintf("%s (código %s)", message, strings.Trim(string(e.Code), `"`))
	}
	return fmt.Errorf("%s: %s", op, message)
}

// StartQR asks the Tuya cloud for a login token. The caller renders the QR
// content returned by QRContent and waits for the user to scan it.
func (c *CloudClient) StartQR(ctx context.Context, userCode string) (string, error) {
	userCode = strings.TrimSpace(userCode)
	if userCode == "" {
		return "", errors.New("informe o User Code do aplicativo Tuya")
	}
	query := url.Values{}
	query.Set("clientid", SharingClientID)
	query.Set("usercode", userCode)
	query.Set("schema", SharingSchema)
	endpoint := c.loginBase() + "/v1.0/m/life/home-assistant/qrcode/tokens?" + query.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, nil)
	if err != nil {
		return "", err
	}
	res, err := c.client().Do(req)
	if err != nil {
		return "", fmt.Errorf("pedir o QR da Tuya: %w", err)
	}
	defer res.Body.Close()
	var envelope cloudEnvelope
	if err := json.NewDecoder(res.Body).Decode(&envelope); err != nil {
		return "", fmt.Errorf("ler a resposta do QR da Tuya: %w", err)
	}
	if !envelope.Success {
		return "", envelope.err("gerar o QR da Tuya")
	}
	var result struct {
		QRCode string `json:"qrcode"`
	}
	if err := json.Unmarshal(envelope.Result, &result); err != nil || strings.TrimSpace(result.QRCode) == "" {
		return "", errors.New("a Tuya não devolveu o token do QR")
	}
	return result.QRCode, nil
}

// PollQR checks whether the user authorized the QR. The second return value is
// false while the app is still waiting; a real transport error is returned as
// an error so the caller can show it instead of polling forever.
func (c *CloudClient) PollQR(ctx context.Context, qrToken, userCode string) (*CloudLogin, bool, error) {
	qrToken = strings.TrimSpace(qrToken)
	userCode = strings.TrimSpace(userCode)
	if qrToken == "" || userCode == "" {
		return nil, false, errors.New("sessão de QR inválida")
	}
	query := url.Values{}
	query.Set("clientid", SharingClientID)
	query.Set("usercode", userCode)
	endpoint := c.loginBase() + "/v1.0/m/life/home-assistant/qrcode/tokens/" + url.PathEscape(qrToken) + "?" + query.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, false, err
	}
	res, err := c.client().Do(req)
	if err != nil {
		return nil, false, fmt.Errorf("consultar a autorização da Tuya: %w", err)
	}
	defer res.Body.Close()
	var envelope cloudEnvelope
	if err := json.NewDecoder(res.Body).Decode(&envelope); err != nil {
		return nil, false, fmt.Errorf("ler a autorização da Tuya: %w", err)
	}
	if !envelope.Success {
		// "Ainda não autorizado" é o caminho normal enquanto o usuário não
		// escaneia o QR; não é um erro para a tela.
		return nil, false, nil
	}
	var login CloudLogin
	if err := json.Unmarshal(envelope.Result, &login); err != nil {
		return nil, false, fmt.Errorf("ler os dados da autorização: %w", err)
	}
	login.T = envelope.T
	if strings.TrimSpace(login.Endpoint) == "" {
		return nil, false, errors.New("a Tuya autorizou, mas não devolveu o endereço da API")
	}
	return &login, true, nil
}

// ListDevices returns every device of every home linked to the authorized
// account. The call is encrypted and signed exactly like the SDK does.
func (c *CloudClient) ListDevices(ctx context.Context, login *CloudLogin) ([]CloudDevice, error) {
	if login == nil || strings.TrimSpace(login.AccessToken) == "" {
		return nil, errors.New("autorização da Tuya ausente")
	}
	api := cloudAPI{
		client:       c.client(),
		endpoint:     strings.TrimRight(login.Endpoint, "/"),
		accessToken:  login.AccessToken,
		refreshToken: login.RefreshToken,
		clientID:     SharingClientID,
	}
	var homes []struct {
		OwnerID string `json:"ownerId"`
		Name    string `json:"name"`
	}
	if err := api.get(ctx, "/v1.0/m/life/users/homes", nil, &homes); err != nil {
		return nil, err
	}
	devices := make([]CloudDevice, 0)
	seen := map[string]bool{}
	for _, home := range homes {
		if strings.TrimSpace(home.OwnerID) == "" {
			continue
		}
		var list []CloudDevice
		if err := api.get(ctx, "/v1.0/m/life/ha/home/devices", map[string]string{"homeId": home.OwnerID}, &list); err != nil {
			return nil, err
		}
		for _, device := range list {
			if device.ID == "" || seen[device.ID] {
				continue
			}
			seen[device.ID] = true
			devices = append(devices, device)
		}
	}
	return devices, nil
}

// cloudAPI is the encrypted customer API bound to one authorized terminal.
type cloudAPI struct {
	client       *http.Client
	endpoint     string
	accessToken  string
	refreshToken string
	clientID     string
}

func (a cloudAPI) get(ctx context.Context, path string, params map[string]string, out any) error {
	result, err := a.request(ctx, http.MethodGet, path, params, nil)
	if err != nil {
		return err
	}
	if len(result) == 0 || string(result) == "null" {
		return nil
	}
	if err := json.Unmarshal(result, out); err != nil {
		return fmt.Errorf("ler a resposta da Tuya: %w", err)
	}
	return nil
}

func (a cloudAPI) request(ctx context.Context, method, path string, params, body map[string]string) ([]byte, error) {
	rid := uuid.NewString()
	hashKey := md5Hex(rid + a.refreshToken)
	secret := secretFromRequest(rid, hashKey)

	var queryEnc, bodyEnc string
	values := url.Values{}
	if len(params) > 0 {
		encoded, err := json.Marshal(params)
		if err != nil {
			return nil, err
		}
		queryEnc, err = encryptCloud(secret, encoded)
		if err != nil {
			return nil, err
		}
		values.Set("encdata", queryEnc)
	}
	var payload io.Reader
	if len(body) > 0 {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		bodyEnc, err = encryptCloud(secret, encoded)
		if err != nil {
			return nil, err
		}
		payload = bytes.NewReader(mustJSON(map[string]string{"encdata": bodyEnc}))
	}
	target := a.endpoint + path
	if encoded := values.Encode(); encoded != "" {
		target += "?" + encoded
	}
	req, err := http.NewRequestWithContext(ctx, method, target, payload)
	if err != nil {
		return nil, err
	}
	now := time.Now().UnixMilli()
	headers := map[string]string{
		"X-appKey":    a.clientID,
		"X-requestId": rid,
		"X-sid":       "",
		"X-time":      fmt.Sprintf("%d", now),
		"X-token":     a.accessToken,
	}
	req.Header.Set("Content-Type", "application/json")
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	req.Header.Set("X-sign", cloudSign(hashKey, queryEnc, bodyEnc, headers))

	res, err := a.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("falar com a API da Tuya: %w", err)
	}
	defer res.Body.Close()
	var envelope cloudEnvelope
	if err := json.NewDecoder(res.Body).Decode(&envelope); err != nil {
		return nil, fmt.Errorf("ler a resposta da API da Tuya: %w", err)
	}
	if !envelope.Success {
		return nil, envelope.err("API da Tuya")
	}
	return decryptCloudResult(envelope.Result, secret)
}

func secretFromRequest(rid, hashKey string) string {
	mac := hmac.New(sha256.New, []byte(rid))
	mac.Write([]byte(hashKey))
	return hex.EncodeToString(mac.Sum(nil))[:16]
}

func cloudSign(hashKey, queryEnc, bodyEnc string, headers map[string]string) string {
	order := []string{"X-appKey", "X-requestId", "X-sid", "X-time", "X-token"}
	parts := make([]string, 0, len(order))
	for _, key := range order {
		value := headers[key]
		if value != "" {
			parts = append(parts, key+"="+value)
		}
	}
	sign := strings.Join(parts, "||")
	sign += queryEnc + bodyEnc
	mac := hmac.New(sha256.New, []byte(hashKey))
	mac.Write([]byte(sign))
	return hex.EncodeToString(mac.Sum(nil))
}

func encryptCloud(secret string, plain []byte) (string, error) {
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

func decryptCloud(secret, encoded string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	if len(raw) < 12 {
		return nil, errors.New("resposta cifrada da Tuya curta demais")
	}
	block, err := aes.NewCipher([]byte(secret))
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	plain, err := gcm.Open(nil, raw[:12], raw[12:], nil)
	if err != nil {
		return nil, fmt.Errorf("decifrar a resposta da Tuya: %w", err)
	}
	return plain, nil
}

func decryptCloudResult(raw json.RawMessage, secret string) ([]byte, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	if raw[0] == '"' {
		var encoded string
		if err := json.Unmarshal(raw, &encoded); err != nil {
			return nil, err
		}
		return decryptCloud(secret, encoded)
	}
	return raw, nil
}

func md5Hex(value string) string {
	sum := md5.Sum([]byte(value))
	return hex.EncodeToString(sum[:])
}

func mustJSON(value any) []byte {
	encoded, _ := json.Marshal(value)
	return encoded
}
