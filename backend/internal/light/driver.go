package light

import (
	"context"
	"fmt"
	"math"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/MXVXID/tiny-go/tuya"
	"github.com/MXVXID/tiny-go/tuya/scanner"
)

type Driver interface {
	Discover(context.Context, Light, Credentials) (string, float64, error)
	Status(context.Context, Light, Credentials) (State, error)
	Apply(context.Context, Light, Credentials, StatePatch) (State, error)
}

type TuyaDriver struct{}

func NewTuyaDriver() *TuyaDriver { return &TuyaDriver{} }

func (d *TuyaDriver) Discover(ctx context.Context, item Light, cred Credentials) (string, float64, error) {
	if cred.LastIP != "" {
		if version, ok := probeVersions(ctx, item.DeviceID, cred.LocalKey, cred.LastIP, item.ProtocolVersion); ok {
			return cred.LastIP, version, nil
		}
	}
	result := make(chan *scanner.ScanResult, 1)
	go func() {
		found, _ := scanner.Scan(scanner.Options{Scantime: 3, TCPTimeout: 250 * time.Millisecond})
		result <- found
	}()
	select {
	case <-ctx.Done():
		return "", 0, ctx.Err()
	case found := <-result:
		if found != nil {
			for _, candidate := range found.Devices {
				if candidate.ID == item.DeviceID {
					if version, ok := probeVersions(ctx, item.DeviceID, cred.LocalKey, candidate.IP, candidate.Version); ok {
						return candidate.IP, version, nil
					}
				}
			}
		}
	}
	if cred.LastIP != "" {
		if ip, version, ok := scanNearby(ctx, item.DeviceID, cred.LocalKey, cred.LastIP, item.ProtocolVersion); ok {
			return ip, version, nil
		}
	}
	return "", 0, fmt.Errorf("light not found on the local network")
}

func (d *TuyaDriver) Status(ctx context.Context, item Light, cred Credentials) (State, error) {
	if cred.LastIP == "" {
		return State{}, fmt.Errorf("light address is not known")
	}
	dev := newDevice(item, cred)
	defer dev.Close()
	type answer struct {
		result *tuya.StatusResult
		err    error
	}
	done := make(chan answer, 1)
	go func() { result, err := dev.Status(); done <- answer{result, err} }()
	select {
	case <-ctx.Done():
		dev.Close()
		return State{}, ctx.Err()
	case out := <-done:
		if out.err != nil {
			return State{}, out.err
		}
		if out.result == nil || out.result.Err != nil {
			if out.result != nil && out.result.Err != nil {
				return State{}, out.result.Err
			}
			return State{}, fmt.Errorf("empty response from light")
		}
		return decodeState(out.result.Dps, cred.Mapping), nil
	}
}

func (d *TuyaDriver) Apply(ctx context.Context, item Light, cred Credentials, patch StatePatch) (State, error) {
	if cred.LastIP == "" {
		return State{}, fmt.Errorf("light address is not known")
	}
	values, err := encodePatch(patch, cred.Mapping)
	if err != nil {
		return State{}, err
	}
	if len(values) == 0 {
		return d.Status(ctx, item, cred)
	}
	dev := newDevice(item, cred)
	defer dev.Close()
	type answer struct {
		result *tuya.StatusResult
		err    error
	}
	done := make(chan answer, 1)
	go func() { result, err := dev.SetMultipleValues(values); done <- answer{result, err} }()
	select {
	case <-ctx.Done():
		dev.Close()
		return State{}, ctx.Err()
	case out := <-done:
		if out.err != nil {
			return State{}, out.err
		}
		if out.result != nil && out.result.Err != nil {
			return State{}, out.result.Err
		}
	}
	return d.Status(ctx, item, cred)
}

func newDevice(item Light, cred Credentials) *tuya.Device {
	return tuya.NewDevice(tuya.DeviceConfig{ID: item.DeviceID, Address: cred.LastIP, LocalKey: cred.LocalKey, Version: item.ProtocolVersion, Timeout: 2200 * time.Millisecond, Retry: 1})
}

func probeVersions(ctx context.Context, id, key, ip string, preferred float64) (float64, bool) {
	versions := []float64{preferred, 3.5, 3.4, 3.3, 3.1}
	seen := map[float64]bool{}
	for _, version := range versions {
		if seen[version] || !validVersion(version) {
			continue
		}
		seen[version] = true
		select {
		case <-ctx.Done():
			return 0, false
		default:
		}
		dev := tuya.NewDevice(tuya.DeviceConfig{ID: id, Address: ip, LocalKey: key, Version: version, Timeout: 900 * time.Millisecond, Retry: 1})
		result, err := dev.Status()
		dev.Close()
		if err == nil && result != nil && result.Err == nil && len(result.Dps) > 0 {
			return version, true
		}
	}
	return 0, false
}

func scanNearby(ctx context.Context, id, key, formerIP string, preferred float64) (string, float64, bool) {
	ip := net.ParseIP(formerIP).To4()
	if ip == nil {
		return "", 0, false
	}
	prefix := fmt.Sprintf("%d.%d.%d.", ip[0], ip[1], ip[2])
	type candidate struct {
		ip      string
		version float64
	}
	found := make(chan candidate, 1)
	jobs := make(chan string)
	var wg sync.WaitGroup
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for address := range jobs {
				if version, ok := probeVersions(ctx, id, key, address, preferred); ok {
					select {
					case found <- candidate{address, version}:
					default:
					}
					return
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for i := 1; i < 255; i++ {
			address := prefix + strconv.Itoa(i)
			if address == formerIP {
				continue
			}
			select {
			case <-ctx.Done():
				return
			case jobs <- address:
			}
		}
	}()
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case value := <-found:
		return value.ip, value.version, true
	case <-done:
		return "", 0, false
	case <-ctx.Done():
		return "", 0, false
	}
}

func decodeState(dps map[string]any, mapping DPMapping) State {
	state := State{Online: true, Mode: "white", TemperatureKelvin: 4000, UpdatedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	state.Power, _ = dps[strconv.Itoa(mapping.Power)].(bool)
	if value, ok := dps[strconv.Itoa(mapping.Mode)].(string); ok {
		if value == "colour" || value == "color" {
			state.Mode = "color"
		} else {
			state.Mode = value
		}
	}
	scale := mapping.Scale
	if scale <= 0 {
		scale = 1000
	}
	state.Brightness = scaledInt(dps[strconv.Itoa(mapping.Brightness)], scale, 100)
	temp := scaledInt(dps[strconv.Itoa(mapping.Temperature)], scale, 1000)
	if temp >= 0 {
		state.TemperatureKelvin = 2700 + int(math.Round(float64(temp)*3.8))
	}
	if value, ok := dps[strconv.Itoa(mapping.Color)].(string); ok {
		state.Color = decodeColor(value, scale)
		if state.Brightness == 0 {
			state.Brightness = state.Color.Value
		}
	}
	return state
}

func encodePatch(patch StatePatch, mapping DPMapping) (map[string]any, error) {
	values := map[string]any{}
	scale := mapping.Scale
	if scale <= 0 {
		scale = 1000
	}
	if patch.Power != nil {
		values[strconv.Itoa(mapping.Power)] = *patch.Power
	}
	if patch.Mode != nil {
		if *patch.Mode != "white" && *patch.Mode != "color" {
			return nil, fmt.Errorf("mode must be white or color")
		}
		mode := *patch.Mode
		if mode == "color" {
			mode = "colour"
		}
		values[strconv.Itoa(mapping.Mode)] = mode
	}
	if patch.Brightness != nil {
		if *patch.Brightness < 1 || *patch.Brightness > 100 {
			return nil, fmt.Errorf("brightness must be between 1 and 100")
		}
		values[strconv.Itoa(mapping.Brightness)] = int(math.Round(float64(*patch.Brightness*scale) / 100))
	}
	if patch.TemperatureKelvin != nil {
		if *patch.TemperatureKelvin < 2700 || *patch.TemperatureKelvin > 6500 {
			return nil, fmt.Errorf("temperatureKelvin must be between 2700 and 6500")
		}
		raw := int(math.Round(float64(*patch.TemperatureKelvin-2700) / 3.8))
		values[strconv.Itoa(mapping.Temperature)] = raw
		values[strconv.Itoa(mapping.Mode)] = "white"
	}
	if patch.Color != nil {
		c := *patch.Color
		if c.Hue < 0 || c.Hue > 360 || c.Saturation < 0 || c.Saturation > 100 || c.Value < 1 || c.Value > 100 {
			return nil, fmt.Errorf("invalid HSV color")
		}
		values[strconv.Itoa(mapping.Color)] = fmt.Sprintf("%04x%04x%04x", c.Hue, c.Saturation*scale/100, c.Value*scale/100)
		values[strconv.Itoa(mapping.Mode)] = "colour"
	}
	return values, nil
}

func decodeColor(value string, scale int) Color {
	value = strings.TrimSpace(value)
	if len(value) >= 12 {
		h, _ := strconv.ParseInt(value[0:4], 16, 32)
		s, _ := strconv.ParseInt(value[4:8], 16, 32)
		v, _ := strconv.ParseInt(value[8:12], 16, 32)
		return Color{Hue: int(h), Saturation: int(s) * 100 / scale, Value: int(v) * 100 / scale}
	}
	if len(value) >= 10 {
		h, _ := strconv.ParseInt(value[0:4], 16, 32)
		s, _ := strconv.ParseInt(value[4:6], 16, 32)
		v, _ := strconv.ParseInt(value[6:10], 16, 32)
		return Color{Hue: int(h), Saturation: int(s), Value: int(v)}
	}
	return Color{}
}

func scaledInt(value any, scale, target int) int {
	var raw int
	switch v := value.(type) {
	case float64:
		raw = int(v)
	case int:
		raw = v
	case int64:
		raw = int(v)
	default:
		return 0
	}
	if scale == 0 {
		return raw
	}
	return int(math.Round(float64(raw*target) / float64(scale)))
}
