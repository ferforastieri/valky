package light

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/ferforastieri/valky/backend/internal/light/tuya"
)

// The data points a Tuya bulb exposes differ per firmware generation. The
// layouts below are the ones the vendor application itself uses; the community
// documents them in tinytuya (https://github.com/jasonacox/tinytuya). The
// adapter detects which layout a bulb speaks while pairing and stores it, so a
// device that reports an unknown layout can be corrected without a new build.
//
//	layout "modern"  switch=20 mode=21 brightness=22 temperature=23 colour=24
//	layout "legacy"  switch=1  mode=2  brightness=3  temperature=4  colour=5
//	layout "white"   switch=1  ---     brightness=2  temperature=3  ---
//
// Brightness and temperature travel as raw device values; the adapter keeps the
// neutral model in percentages and Kelvin. Colour travels as a lowercase hex
// string: rgb8 is "rrggbb" and hsv16 is "hhhhssssvvvv" (hue in degrees, the
// other two scaled to 1000).
type DPProfile struct {
	Name           string `json:"name"`
	Switch         string `json:"switch,omitempty"`
	Mode           string `json:"mode,omitempty"`
	Brightness     string `json:"brightness,omitempty"`
	Temperature    string `json:"temperature,omitempty"`
	Colour         string `json:"colour,omitempty"`
	BrightnessMin  int    `json:"brightnessMin"`
	BrightnessMax  int    `json:"brightnessMax"`
	TemperatureMin int    `json:"temperatureMin"`
	TemperatureMax int    `json:"temperatureMax"`
	ColourFormat   string `json:"colourFormat,omitempty"`
}

// Kelvin bounds used by the neutral model. Tuya bulbs do not report Kelvin, so
// the raw temperature value is stretched across the range a household bulb
// covers.
const (
	WarmestKelvin = 2700
	CoolestKelvin = 6500
)

var (
	modernProfile = DPProfile{
		Name: "modern", Switch: "20", Mode: "21", Brightness: "22", Temperature: "23", Colour: "24",
		BrightnessMin: 10, BrightnessMax: 1000, TemperatureMin: 0, TemperatureMax: 1000, ColourFormat: "hsv16",
	}
	legacyProfile = DPProfile{
		Name: "legacy", Switch: "1", Mode: "2", Brightness: "3", Temperature: "4", Colour: "5",
		BrightnessMin: 25, BrightnessMax: 255, TemperatureMin: 0, TemperatureMax: 255, ColourFormat: "rgb8",
	}
	whiteProfile = DPProfile{
		Name: "white", Switch: "1", Brightness: "2", Temperature: "3",
		BrightnessMin: 25, BrightnessMax: 255, TemperatureMin: 0, TemperatureMax: 255,
	}
)

// detectProfile picks the layout that matches the data points a device reports.
func detectProfile(dps map[string]any) DPProfile {
	if hasAnyKey(dps, "20", "22", "23", "24") {
		return modernProfile
	}
	if hasAnyKey(dps, "5") {
		return legacyProfile
	}
	if hasAnyKey(dps, "2", "3") {
		return whiteProfile
	}
	return modernProfile
}

func hasAnyKey(dps map[string]any, keys ...string) bool {
	for _, key := range keys {
		if _, ok := dps[key]; ok {
			return true
		}
	}
	return false
}

func (p DPProfile) capabilities() Capabilities {
	return Capabilities{
		Brightness:       p.Brightness != "",
		Color:            p.Colour != "",
		ColorTemperature: p.Temperature != "",
	}
}

func (p DPProfile) encode() string {
	encoded, err := json.Marshal(p)
	if err != nil {
		return ""
	}
	return string(encoded)
}

func decodeProfile(raw string) (DPProfile, bool) {
	if strings.TrimSpace(raw) == "" || strings.TrimSpace(raw) == "{}" {
		return DPProfile{}, false
	}
	var profile DPProfile
	if json.Unmarshal([]byte(raw), &profile) != nil || profile.Switch == "" {
		return DPProfile{}, false
	}
	return profile, true
}

// ---------------------------------------------------------------------------
// Value conversions
// ---------------------------------------------------------------------------

func percentToRaw(percent, minimum, maximum int) int {
	percent = clamp(percent, 0, 100)
	return minimum + int(math.Round(float64(maximum-minimum)*float64(percent)/100))
}

func rawToPercent(raw, minimum, maximum int) int {
	if maximum <= minimum {
		return 0
	}
	return clamp(int(math.Round(float64(raw-minimum)*100/float64(maximum-minimum))), 0, 100)
}

func kelvinToRaw(kelvin, minimum, maximum int) int {
	kelvin = clamp(kelvin, WarmestKelvin, CoolestKelvin)
	ratio := float64(kelvin-WarmestKelvin) / float64(CoolestKelvin-WarmestKelvin)
	return minimum + int(math.Round(float64(maximum-minimum)*ratio))
}

func rawToKelvin(raw, minimum, maximum int) int {
	if maximum <= minimum {
		return WarmestKelvin
	}
	ratio := float64(clamp(raw, minimum, maximum)-minimum) / float64(maximum-minimum)
	return WarmestKelvin + int(math.Round(float64(CoolestKelvin-WarmestKelvin)*ratio))
}

func clamp(value, minimum, maximum int) int {
	if value < minimum {
		return minimum
	}
	if value > maximum {
		return maximum
	}
	return value
}

// encodeColour turns the neutral HSV model into the hex string the device
// expects for its layout.
func (p DPProfile) encodeColour(colour Color) string {
	hue := clamp(colour.Hue, 0, 360)
	saturation := clamp(colour.Saturation, 0, 100)
	value := clamp(colour.Value, 0, 100)
	switch p.ColourFormat {
	case "rgb8":
		red, green, blue := hsvToRGB(hue, saturation, value)
		return fmt.Sprintf("%02x%02x%02x", red, green, blue)
	default:
		scaledSaturation := int(math.Round(float64(saturation) * 10))
		scaledValue := int(math.Round(float64(value) * 10))
		return fmt.Sprintf("%04x%04x%04x", hue, scaledSaturation, scaledValue)
	}
}

// decodeColour is the inverse of encodeColour; unknown strings keep the value
// the caller already had.
func (p DPProfile) decodeColour(raw string, fallback Color) Color {
	clean := strings.ToLower(strings.TrimSpace(raw))
	switch p.ColourFormat {
	case "rgb8":
		if len(clean) < 6 {
			return fallback
		}
		red, errR := parseHexByte(clean[0:2])
		green, errG := parseHexByte(clean[2:4])
		blue, errB := parseHexByte(clean[4:6])
		if errR != nil || errG != nil || errB != nil {
			return fallback
		}
		hue, saturation, value := rgbToHSV(red, green, blue)
		return Color{Hue: hue, Saturation: saturation, Value: value}
	default:
		if len(clean) < 12 {
			return fallback
		}
		hue, errH := parseHexInt(clean[0:4])
		saturation, errS := parseHexInt(clean[4:8])
		value, errV := parseHexInt(clean[8:12])
		if errH != nil || errS != nil || errV != nil {
			return fallback
		}
		return Color{
			Hue:        clamp(hue, 0, 360),
			Saturation: clamp(int(math.Round(float64(saturation)/10)), 0, 100),
			Value:      clamp(int(math.Round(float64(value)/10)), 0, 100),
		}
	}
}

func parseHexByte(raw string) (int, error) {
	value, err := parseHexInt(raw)
	return value, err
}

func parseHexInt(raw string) (int, error) {
	var value int
	if _, err := fmt.Sscanf(strings.TrimSpace(raw), "%x", &value); err != nil {
		return 0, err
	}
	return value, nil
}

func hsvToRGB(hue, saturation, value int) (int, int, int) {
	if saturation == 0 {
		level := value * 255 / 100
		return level, level, level
	}
	h := float64(hue%360) / 60
	s := float64(saturation) / 100
	v := float64(value) / 100
	index := int(math.Floor(h)) % 6
	fraction := h - math.Floor(h)
	p := v * (1 - s)
	q := v * (1 - s*fraction)
	t := v * (1 - s*(1-fraction))
	var red, green, blue float64
	switch index {
	case 0:
		red, green, blue = v, t, p
	case 1:
		red, green, blue = q, v, p
	case 2:
		red, green, blue = p, v, t
	case 3:
		red, green, blue = p, q, v
	case 4:
		red, green, blue = t, p, v
	default:
		red, green, blue = v, p, q
	}
	return int(math.Round(red * 255)), int(math.Round(green * 255)), int(math.Round(blue * 255))
}

func rgbToHSV(red, green, blue int) (int, int, int) {
	r := float64(red) / 255
	g := float64(green) / 255
	b := float64(blue) / 255
	maximum := math.Max(r, math.Max(g, b))
	minimum := math.Min(r, math.Min(g, b))
	delta := maximum - minimum
	var hue float64
	switch {
	case delta == 0:
		hue = 0
	case maximum == r:
		hue = 60 * math.Mod((g-b)/delta, 6)
	case maximum == g:
		hue = 60 * ((b-r)/delta + 2)
	default:
		hue = 60 * ((r-g)/delta + 4)
	}
	if hue < 0 {
		hue += 360
	}
	saturation := 0.0
	if maximum > 0 {
		saturation = delta / maximum
	}
	return int(math.Round(hue)), int(math.Round(saturation * 100)), int(math.Round(maximum * 100))
}

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------

// Candidate is a light found on the local network that is not registered yet.
type Candidate struct {
	Address    string  `json:"address"`
	DeviceID   string  `json:"deviceId,omitempty"`
	Version    float64 `json:"version,omitempty"`
	ProductKey string  `json:"productKey,omitempty"`
	MAC        string  `json:"mac,omitempty"`
}

// ProvisionResult is what the adapter learned about a device while pairing.
type ProvisionResult struct {
	State        State
	Capabilities Capabilities
	Version      float64
	Mapping      string
}

// Scanner is implemented by drivers that can enumerate candidate devices on the
// local network and validate a device's credentials before it is registered.
type Scanner interface {
	Scan(ctx context.Context, duration time.Duration, probePorts bool) ([]Candidate, error)
	Provision(ctx context.Context, item Light, cred Credentials) (ProvisionResult, error)
}

// TuyaDriver talks to Tuya-based lighting over the local network only.
type TuyaDriver struct {
	timeout time.Duration
}

func NewTuyaDriver() *TuyaDriver { return &TuyaDriver{timeout: 6 * time.Second} }

func (d *TuyaDriver) Available() bool { return true }

// Scan enumerates candidate devices. A device that has never been paired
// answers with its identity; a provisioned one answers with its address only,
// because the firmware stops revealing the device ID on the plain broadcast.
func (d *TuyaDriver) Scan(ctx context.Context, duration time.Duration, probePorts bool) ([]Candidate, error) {
	// A sondagem de porta roda em todas as sub-redes locais: com um tempo curto
	// ela cabe no ciclo do pareamento sem segurar a próxima tentativa.
	timeout := 3 * time.Second
	if probePorts {
		timeout = 250 * time.Millisecond
	}
	devices, err := tuya.Discover(ctx, tuya.ScanOptions{Duration: duration, TCPProbe: probePorts, Timeout: timeout})
	if err != nil {
		return nil, err
	}
	out := make([]Candidate, 0, len(devices))
	for _, device := range devices {
		out = append(out, Candidate{
			Address:    device.Address,
			DeviceID:   device.ID,
			Version:    device.Version,
			ProductKey: device.ProductKey,
			MAC:        device.MAC,
		})
	}
	return out, nil
}

// Provision validates a local key and learns the device layout: the working
// protocol version, the data points and the resulting capabilities.
func (d *TuyaDriver) Provision(ctx context.Context, item Light, cred Credentials) (ProvisionResult, error) {
	if strings.TrimSpace(cred.Address) == "" {
		address, err := d.Discover(ctx, item, cred)
		if err != nil {
			return ProvisionResult{}, err
		}
		cred.Address = address
	}
	dps, version, err := tuya.Identify(ctx, tuya.Options{
		DeviceID: item.DeviceID,
		Address:  cred.Address,
		LocalKey: cred.Secret,
		Version:  item.ProtocolVersion,
		Timeout:  d.timeout,
	})
	if err != nil {
		return ProvisionResult{}, provisionFailure(err)
	}
	profile := detectProfile(dps)
	state := profile.stateFromDPS(dps, State{})
	state.Online = true
	return ProvisionResult{
		State:        state,
		Capabilities: profile.capabilities(),
		Version:      version,
		Mapping:      profile.encode(),
	}, nil
}

// provisionFailure traduz a falha do protocolo para algo que o aplicativo possa
// explicar. Uma lâmpada que respondeu e recusou a chave (integridade do quadro
// não confere) é um problema diferente de uma que nunca respondeu (porta
// fechada ou tempo esgotado).
func provisionFailure(err error) error {
	var netErr net.Error
	switch {
	case errors.Is(err, tuya.ErrBadKey),
		errors.Is(err, tuya.ErrBadCRC),
		errors.Is(err, tuya.ErrDeviceReject):
		return fmt.Errorf("%w: %v", ErrLightKeyRefused, err)
	case errors.Is(err, tuya.ErrNoResponse),
		errors.Is(err, context.DeadlineExceeded),
		errors.As(err, &netErr):
		return fmt.Errorf("%w: %v", ErrLightUnreachable, err)
	default:
		return err
	}
}

// Discover finds the current address of a registered device. The device ID is
// matched against the discovery answers; when the firmware hides newer devices
// from the plain broadcast the previously stored address is kept.
func (d *TuyaDriver) Discover(ctx context.Context, item Light, cred Credentials) (string, error) {
	devices, err := tuya.Discover(ctx, tuya.ScanOptions{Duration: 6 * time.Second, Timeout: 3 * time.Second})
	if err != nil {
		return "", err
	}
	for _, device := range devices {
		if device.ID != "" && device.ID == item.DeviceID {
			return device.Address, nil
		}
	}
	// O broadcast não atravessa a rede do container: quando ele não encontra,
	// varremos a faixa do último endereço conhecido e conferimos cada candidato
	// com a chave da lâmpada (chave errada não responde estado). É isso que traz
	// a lâmpada de volta sozinha quando o roteador troca o IP dela.
	if known := strings.TrimSpace(item.LastAddress); known != "" {
		// A ronda dá 8 segundos por lâmpada: a varredura e as sondagens correm
		// JUNTAS e com prazo curto, senão o tempo acaba antes de achar a
		// lâmpada (era por isso que ela nunca era encontrada sozinha).
		scanCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		candidates := tuya.ScanAddresses(scanCtx, known, 6668, 250*time.Millisecond)
		cancel()
		if len(candidates) > 0 {
			found := make(chan string, len(candidates))
			var probes sync.WaitGroup
			for _, candidate := range candidates {
				probes.Add(1)
				go func(address string) {
					defer probes.Done()
					try := cred
					try.Address = address
					probeCtx, probeCancel := context.WithTimeout(ctx, 1500*time.Millisecond)
					defer probeCancel()
					if _, err := d.Status(probeCtx, item, try); err == nil {
						select {
						case found <- address:
						default:
						}
					}
				}(candidate)
			}
			go func() { probes.Wait(); close(found) }()
			if address, ok := <-found; ok {
				return address, nil
			}
		}
	}
	if credential := strings.TrimSpace(item.LastAddress); credential != "" {
		return credential, nil
	}
	if strings.TrimSpace(cred.Address) != "" {
		return cred.Address, nil
	}
	return "", fmt.Errorf("lâmpada não encontrada na rede local")
}

func (d *TuyaDriver) Status(ctx context.Context, item Light, cred Credentials) (State, error) {
	profile, ok := decodeProfile(item.DPMapping)
	if !ok {
		profile = modernProfile
	}
	client, err := d.connect(ctx, item, cred)
	if err != nil {
		return State{}, err
	}
	defer client.Close()
	dps, err := client.Status(ctx)
	if err != nil {
		return State{}, err
	}
	detected := detectProfile(dps)
	if detected.Name != profile.Name {
		profile = detected
	}
	state := profile.stateFromDPS(dps, State{})
	state.Online = true
	return state, nil
}

func (d *TuyaDriver) Apply(ctx context.Context, item Light, cred Credentials, patch StatePatch) (State, error) {
	profile, ok := decodeProfile(item.DPMapping)
	if !ok {
		profile = modernProfile
	}
	client, err := d.connect(ctx, item, cred)
	if err != nil {
		return State{}, err
	}
	defer client.Close()

	current := map[string]any{}
	if reported, statusErr := client.Status(ctx); statusErr == nil {
		current = reported
		profile = detectProfile(reported)
	}
	dps := profile.patchToDPS(patch, current)
	if len(dps) == 0 {
		state := profile.stateFromDPS(current, State{})
		state.Online = true
		return state, nil
	}
	reported, err := client.Set(ctx, dps)
	if err != nil {
		return State{}, err
	}
	merged := map[string]any{}
	for key, value := range current {
		merged[key] = value
	}
	for key, value := range dps {
		merged[key] = value
	}
	for key, value := range reported {
		merged[key] = value
	}
	state := profile.stateFromDPS(merged, State{})
	state.Online = true
	return state, nil
}

func (d *TuyaDriver) connect(ctx context.Context, item Light, cred Credentials) (*tuya.Client, error) {
	timeout := d.timeout
	if deadline, ok := ctx.Deadline(); ok {
		if remaining := time.Until(deadline); remaining > 0 && remaining < timeout {
			timeout = remaining
		}
	}
	return tuya.Dial(ctx, tuya.Options{
		DeviceID: item.DeviceID,
		Address:  cred.Address,
		LocalKey: cred.Secret,
		Version:  item.ProtocolVersion,
		Timeout:  timeout,
	})
}

// ---------------------------------------------------------------------------
// Data point translation
// ---------------------------------------------------------------------------

func (p DPProfile) stateFromDPS(dps map[string]any, previous State) State {
	state := previous
	if p.Switch != "" {
		if value, ok := dps[p.Switch].(bool); ok {
			state.Power = value
		}
	}
	if p.Mode != "" {
		if value, ok := dps[p.Mode].(string); ok && value != "" {
			state.Mode = normalizeMode(value)
		}
	}
	if state.Mode == "" {
		state.Mode = neutralModeWhite
	}
	if p.Brightness != "" {
		if raw, ok := numeric(dps[p.Brightness]); ok {
			state.Brightness = rawToPercent(raw, p.BrightnessMin, p.BrightnessMax)
		}
	}
	if p.Temperature != "" {
		if raw, ok := numeric(dps[p.Temperature]); ok {
			state.TemperatureKelvin = rawToKelvin(raw, p.TemperatureMin, p.TemperatureMax)
		}
	}
	if p.Colour != "" {
		if value, ok := dps[p.Colour].(string); ok {
			state.Color = p.decodeColour(value, state.Color)
		}
	}
	return state
}

// Device modes use the vendor spelling ("colour"); the neutral model the API
// and the clients share uses "color".
const (
	deviceModeColour = "colour"
	deviceModeWhite  = "white"
	neutralModeColor = "color"
	neutralModeWhite = "white"
)

func normalizeMode(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case deviceModeColour, neutralModeColor, "scene", "music":
		return neutralModeColor
	default:
		return neutralModeWhite
	}
}

// deviceMode translates the neutral mode into the value the firmware expects.
func deviceMode(neutral string) string {
	if normalizeMode(neutral) == neutralModeColor {
		return deviceModeColour
	}
	return deviceModeWhite
}

// patchToDPS translates a neutral patch into data points, reusing the values the
// device already reported so a change never resets the rest of the light.
func (p DPProfile) patchToDPS(patch StatePatch, current map[string]any) map[string]any {
	dps := map[string]any{}
	mode := p.currentMode(current)
	brightness := p.currentBrightness(current)
	if patch.Mode != nil {
		mode = normalizeMode(*patch.Mode)
	}
	if patch.Brightness != nil {
		brightness = *patch.Brightness
	}
	if patch.Power != nil && p.Switch != "" {
		dps[p.Switch] = *patch.Power
	}
	if patch.Color != nil && p.Colour != "" {
		dps[p.Colour] = p.encodeColour(*patch.Color)
		mode = neutralModeColor
	}
	if patch.TemperatureKelvin != nil && p.Temperature != "" {
		dps[p.Temperature] = kelvinToRaw(*patch.TemperatureKelvin, p.TemperatureMin, p.TemperatureMax)
		mode = neutralModeWhite
	}
	if patch.Brightness != nil && p.Brightness != "" {
		dps[p.Brightness] = percentToRaw(brightness, p.BrightnessMin, p.BrightnessMax)
	}
	if p.Mode != "" && (patch.Mode != nil || patch.Color != nil || patch.TemperatureKelvin != nil) {
		dps[p.Mode] = deviceMode(mode)
	}
	// Turning a light on keeps whichever temperature the device already had:
	// some firmwares refuse a colour temperature written in the same frame as
	// the power-on command, so the neutral model sends them separately.
	return dps
}

func (p DPProfile) currentMode(current map[string]any) string {
	if p.Mode != "" {
		if value, ok := current[p.Mode].(string); ok {
			return normalizeMode(value)
		}
	}
	return neutralModeWhite
}

func (p DPProfile) currentBrightness(current map[string]any) int {
	if p.Brightness != "" {
		if raw, ok := numeric(current[p.Brightness]); ok {
			return rawToPercent(raw, p.BrightnessMin, p.BrightnessMax)
		}
	}
	return 100
}

// numeric accepts the number shapes JSON and the devices produce.
func numeric(value any) (int, bool) {
	switch typed := value.(type) {
	case float64:
		return int(typed), true
	case float32:
		return int(typed), true
	case int:
		return typed, true
	case int64:
		return int(typed), true
	case json.Number:
		parsed, err := typed.Int64()
		return int(parsed), err == nil
	case string:
		var parsed int
		if _, err := fmt.Sscanf(strings.TrimSpace(typed), "%d", &parsed); err != nil {
			return 0, false
		}
		return parsed, true
	default:
		return 0, false
	}
}

var _ Driver = (*TuyaDriver)(nil)
