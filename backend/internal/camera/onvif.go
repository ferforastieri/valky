package camera

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type ONVIFClient struct{ http *http.Client }

func NewONVIFClient() *ONVIFClient {
	return &ONVIFClient{http: &http.Client{Timeout: 30 * time.Second}}
}

func (c *ONVIFClient) Probe(ctx context.Context, host string, port int, username, password string) (Capabilities, string, ServiceAddresses, error) {
	endpoint := fmt.Sprintf("http://%s:%d/onvif/device_service", host, port)
	body, err := c.call(ctx, endpoint, username, password, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities", `<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>`)
	if err != nil {
		return Capabilities{}, "", ServiceAddresses{}, err
	}
	text := string(body)
	caps := Capabilities{
		Events:   strings.Contains(text, "Events") || strings.Contains(text, "event_service"),
		PTZ:      strings.Contains(text, "PTZ") || strings.Contains(text, "ptz_service"),
		Snapshot: strings.Contains(text, "Media") || strings.Contains(text, "media_service"),
	}
	services := ServiceAddresses{
		Media:  findXAddr(text, "Media"),
		Events: findXAddr(text, "Events"),
		PTZ:    findXAddr(text, "PTZ"),
	}
	mediaURL := services.Media
	if mediaURL == "" {
		mediaURL = fmt.Sprintf("http://%s:%d/onvif/Media", host, port)
		services.Media = mediaURL
	}
	profiles, err := c.call(ctx, mediaURL, username, password, "http://www.onvif.org/ver10/media/wsdl/GetProfiles", `<trt:GetProfiles/>`)
	if err != nil {
		return caps, "", services, err
	}
	var response struct {
		Profiles []struct {
			Token string `xml:"token,attr"`
			Audio any    `xml:"AudioEncoderConfiguration"`
			PTZ   any    `xml:"PTZConfiguration"`
		} `xml:"Body>GetProfilesResponse>Profiles"`
	}
	_ = xml.Unmarshal(profiles, &response)
	profile := ""
	if len(response.Profiles) > 0 {
		profile = response.Profiles[0].Token
	}
	profileText := string(profiles)
	if profile == "" {
		profile = attrAfter(profileText, "Profiles", "token")
	}
	caps.Audio = strings.Contains(profileText, "AudioEncoderConfiguration")
	caps.PTZ = caps.PTZ && strings.Contains(profileText, "PTZConfiguration")
	caps.Zoom = strings.Contains(profileText, "ZoomLimits")
	if caps.PTZ {
		ptzURL := services.PTZ
		if ptzURL == "" {
			ptzURL = fmt.Sprintf("http://%s:%d/onvif/PTZ", host, port)
			services.PTZ = ptzURL
		}
		presetPayload := fmt.Sprintf(`<tptz:GetPresets><tptz:ProfileToken>%s</tptz:ProfileToken></tptz:GetPresets>`, escape(profile))
		_, presetErr := c.call(ctx, ptzURL, username, password, "http://www.onvif.org/ver20/ptz/wsdl/GetPresets", presetPayload)
		caps.Presets = presetErr == nil
	}
	if caps.Events && services.Events == "" {
		services.Events = fmt.Sprintf("http://%s:%d/onvif/event_service", host, port)
	}
	return caps, profile, services, nil
}

func (c *ONVIFClient) PTZ(ctx context.Context, cam Camera, cred Credentials, command PTZCommand) error {
	endpoint := cam.Services.PTZ
	if endpoint == "" {
		endpoint = fmt.Sprintf("http://%s:%d/onvif/PTZ", cam.Host, cam.Port)
	}
	if command.Action == "stop" {
		_, err := c.call(ctx, endpoint, cred.Username, cred.Password, "http://www.onvif.org/ver20/ptz/wsdl/Stop", fmt.Sprintf(`<tptz:Stop><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>`, escape(cam.ProfileToken)))
		return err
	}
	if command.Action == "preset" {
		if command.PresetToken == "" {
			return fmt.Errorf("preset token is required")
		}
		payload := fmt.Sprintf(`<tptz:GotoPreset><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:PresetToken>%s</tptz:PresetToken></tptz:GotoPreset>`, escape(cam.ProfileToken), escape(command.PresetToken))
		_, err := c.call(ctx, endpoint, cred.Username, cred.Password, "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset", payload)
		return err
	}
	if command.Action != "move" && command.Action != "relative" {
		return fmt.Errorf("unsupported PTZ action")
	}
	command.Pan = clamp(command.Pan)
	command.Tilt = clamp(command.Tilt)
	command.Zoom = clamp(command.Zoom)
	action := "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove"
	payload := fmt.Sprintf(`<tptz:ContinuousMove><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:Velocity><tt:PanTilt x="%.3f" y="%.3f"/><tt:Zoom x="%.3f"/></tptz:Velocity></tptz:ContinuousMove>`, escape(cam.ProfileToken), command.Pan, command.Tilt, command.Zoom)
	if command.Action == "relative" {
		action = "http://www.onvif.org/ver20/ptz/wsdl/RelativeMove"
		payload = fmt.Sprintf(`<tptz:RelativeMove><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:Translation><tt:PanTilt x="%.3f" y="%.3f"/><tt:Zoom x="%.3f"/></tptz:Translation></tptz:RelativeMove>`, escape(cam.ProfileToken), command.Pan, command.Tilt, command.Zoom)
	}
	_, err := c.call(ctx, endpoint, cred.Username, cred.Password, action, payload)
	return err
}

func (c *ONVIFClient) Presets(ctx context.Context, cam Camera, cred Credentials) ([]Preset, error) {
	endpoint := cam.Services.PTZ
	if endpoint == "" {
		endpoint = fmt.Sprintf("http://%s:%d/onvif/PTZ", cam.Host, cam.Port)
	}
	payload := fmt.Sprintf(`<tptz:GetPresets><tptz:ProfileToken>%s</tptz:ProfileToken></tptz:GetPresets>`, escape(cam.ProfileToken))
	body, err := c.call(ctx, endpoint, cred.Username, cred.Password, "http://www.onvif.org/ver20/ptz/wsdl/GetPresets", payload)
	if err != nil {
		return nil, err
	}
	decoder := xml.NewDecoder(bytes.NewReader(body))
	presets := make([]Preset, 0)
	for {
		token, tokenErr := decoder.Token()
		if tokenErr == io.EOF {
			break
		}
		if tokenErr != nil {
			return nil, tokenErr
		}
		start, ok := token.(xml.StartElement)
		if !ok || start.Name.Local != "Preset" {
			continue
		}
		preset := Preset{}
		for _, attribute := range start.Attr {
			if attribute.Name.Local == "token" {
				preset.Token = attribute.Value
			}
		}
		var value struct {
			Name string `xml:"Name"`
		}
		if err = decoder.DecodeElement(&value, &start); err != nil {
			return nil, err
		}
		preset.Name = strings.TrimSpace(value.Name)
		if preset.Token != "" {
			if preset.Name == "" {
				preset.Name = "Preset " + preset.Token
			}
			presets = append(presets, preset)
		}
	}
	return presets, nil
}

func (c *ONVIFClient) SnapshotURI(ctx context.Context, cam Camera, cred Credentials) (string, error) {
	endpoint := cam.Services.Media
	if endpoint == "" {
		endpoint = fmt.Sprintf("http://%s:%d/onvif/Media", cam.Host, cam.Port)
	}
	payload := fmt.Sprintf(`<trt:GetSnapshotUri><trt:ProfileToken>%s</trt:ProfileToken></trt:GetSnapshotUri>`, escape(cam.ProfileToken))
	body, err := c.call(ctx, endpoint, cred.Username, cred.Password, "http://www.onvif.org/ver10/media/wsdl/GetSnapshotUri", payload)
	if err != nil {
		return "", err
	}
	uri := elementText(body, "Uri")
	if uri == "" {
		return "", fmt.Errorf("camera did not return a snapshot URI")
	}
	return uri, nil
}

// MonitorEvents maintains a renewable ONVIF PullPoint subscription. Returning
// errors are retried with bounded backoff so a camera reboot does not require
// restarting Valkyris.
func (c *ONVIFClient) MonitorEvents(ctx context.Context, cam Camera, cred Credentials, emit func(string, float64)) {
	backoff := time.Second
	for ctx.Err() == nil {
		if err := c.pullSession(ctx, cam, cred, emit); err != nil && ctx.Err() == nil {
			timer := time.NewTimer(backoff)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			if backoff < 30*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = time.Second
	}
}

func (c *ONVIFClient) pullSession(ctx context.Context, cam Camera, cred Credentials, emit func(string, float64)) error {
	eventService := cam.Services.Events
	if eventService == "" {
		eventService = fmt.Sprintf("http://%s:%d/onvif/event_service", cam.Host, cam.Port)
	}
	created, err := c.call(ctx, eventService, cred.Username, cred.Password, "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest", `<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT5M</tev:InitialTerminationTime></tev:CreatePullPointSubscription>`)
	if err != nil {
		return err
	}
	pullPoint := elementText(created, "Address")
	if pullPoint == "" {
		return fmt.Errorf("ONVIF event service returned no pull point")
	}
	session, cancel := context.WithTimeout(ctx, 4*time.Minute)
	defer cancel()
	for session.Err() == nil {
		messages, pullErr := c.call(session, pullPoint, cred.Username, cred.Password, "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest", `<tev:PullMessages><tev:Timeout>PT20S</tev:Timeout><tev:MessageLimit>32</tev:MessageLimit></tev:PullMessages>`)
		if pullErr != nil {
			return pullErr
		}
		for _, kind := range eventKinds(messages) {
			emit(kind, 1)
		}
	}
	return session.Err()
}

func eventKinds(message []byte) []string {
	text := strings.ToLower(string(message))
	active := strings.Contains(text, `value="true"`) || strings.Contains(text, `value="1"`)
	if !active {
		return nil
	}
	seen := make(map[string]bool)
	var kinds []string
	add := func(kind string) {
		if !seen[kind] {
			seen[kind] = true
			kinds = append(kinds, kind)
		}
	}
	if strings.Contains(text, "human") || strings.Contains(text, "person") {
		add("person")
	}
	if strings.Contains(text, "tamper") || strings.Contains(text, "sabotage") {
		add("tamper")
	}
	if strings.Contains(text, "motion") {
		add("motion")
	}
	return kinds
}

func (c *ONVIFClient) call(ctx context.Context, endpoint, username, password, action, payload string) ([]byte, error) {
	header, err := securityHeader(username, password)
	if err != nil {
		return nil, err
	}
	envelope := `<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tev="http://www.onvif.org/ver10/events/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"><s:Header>` + header + `</s:Header><s:Body>` + payload + `</s:Body></s:Envelope>`
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewBufferString(envelope))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", `application/soap+xml; charset=utf-8; action="`+action+`"`)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ONVIF request: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("ONVIF returned %s", resp.Status)
	}
	if bytes.Contains(body, []byte(":Fault>")) {
		return nil, fmt.Errorf("ONVIF SOAP fault")
	}
	return body, nil
}

func elementText(document []byte, localName string) string {
	decoder := xml.NewDecoder(bytes.NewReader(document))
	for {
		token, err := decoder.Token()
		if err != nil {
			return ""
		}
		start, ok := token.(xml.StartElement)
		if !ok || start.Name.Local != localName {
			continue
		}
		var value string
		if decoder.DecodeElement(&value, &start) == nil {
			return strings.TrimSpace(value)
		}
	}
}

func securityHeader(username, password string) (string, error) {
	nonce := make([]byte, 20)
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	created := time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
	digest := sha1.Sum(append(append(append([]byte{}, nonce...), []byte(created)...), []byte(password)...))
	return fmt.Sprintf(`<wsse:Security s:mustUnderstand="1"><wsse:UsernameToken><wsse:Username>%s</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">%s</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">%s</wsse:Nonce><wsu:Created>%s</wsu:Created></wsse:UsernameToken></wsse:Security>`, escape(username), base64.StdEncoding.EncodeToString(digest[:]), base64.StdEncoding.EncodeToString(nonce), created), nil
}

func findXAddr(body, service string) string {
	i := strings.Index(body, service)
	if i < 0 {
		return ""
	}
	tail := body[i:]
	start := strings.Index(tail, "<tt:XAddr>")
	end := strings.Index(tail, "</tt:XAddr>")
	if start < 0 || end < start {
		return ""
	}
	return tail[start+10 : end]
}
func attrAfter(body, element, attr string) string {
	i := strings.Index(body, element)
	if i < 0 {
		return ""
	}
	s := body[i:]
	marker := attr + `="`
	j := strings.Index(s, marker)
	if j < 0 {
		return ""
	}
	s = s[j+len(marker):]
	k := strings.Index(s, `"`)
	if k < 0 {
		return ""
	}
	return s[:k]
}
func escape(v string) string {
	var b bytes.Buffer
	_ = xml.EscapeText(&b, []byte(v))
	return b.String()
}
func clamp(v float64) float64 {
	if v > 1 {
		return 1
	}
	if v < -1 {
		return -1
	}
	return v
}
