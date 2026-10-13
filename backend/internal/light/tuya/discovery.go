package tuya

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Device is a Tuya device found on the local network. The device ID is the
// value the vendor application calls gwId; the local key is never part of a
// discovery result.
type Device struct {
	Address    string  `json:"address"`
	ID         string  `json:"id,omitempty"`
	Version    float64 `json:"version,omitempty"`
	ProductKey string  `json:"productKey,omitempty"`
	MAC        string  `json:"mac,omitempty"`
	Name       string  `json:"name,omitempty"`
	encrypted  bool
}

// ScanOptions configures a discovery run.
type ScanOptions struct {
	// Duration is how long to listen for device broadcasts. Defaults to 8s.
	Duration time.Duration
	// TCPProbe also probes port 6668 on the local subnets to find devices that
	// do not answer the vendor broadcast. Disabled by default because it is the
	// noisiest part of a scan.
	TCPProbe bool
	// Timeout per connection attempt.
	Timeout time.Duration
	// ListenPorts overrides the UDP ports to listen on (tests).
	ListenPorts []int
}

// Discover finds Tuya devices on the local network. It sends the same
// REQ_DEVINFO broadcast the vendor application uses, then collects whatever the
// devices answer. No cloud service is involved and no credentials are needed:
// a device that answers is reported with its address, and with its device ID
// and protocol version when the firmware reveals them.
func Discover(ctx context.Context, opts ScanOptions) ([]Device, error) {
	if opts.Duration <= 0 {
		opts.Duration = 8 * time.Second
	}
	if opts.Timeout <= 0 {
		opts.Timeout = 3 * time.Second
	}
	ports := opts.ListenPorts
	if len(ports) == 0 {
		ports = []int{PortUDP31, PortUDP33, PortUDPApp}
	}
	broadcasts := broadcastAddresses()

	type result struct {
		device Device
		fields map[string]any
	}
	found := map[string]*result{}
	var mu sync.Mutex

	ctx, cancel := context.WithTimeout(ctx, opts.Duration)
	defer cancel()

	var listeners sync.WaitGroup
	for _, port := range ports {
		listeners.Add(1)
		go func(port int) {
			defer listeners.Done()
			listenForDevices(ctx, port, func(address string, payload map[string]any) {
				mu.Lock()
				defer mu.Unlock()
				entry, ok := found[address]
				if !ok {
					entry = &result{device: Device{Address: address}, fields: map[string]any{}}
					found[address] = entry
				}
				for key, value := range payload {
					entry.fields[key] = value
				}
			})
		}(port)
	}

	// Wake the v3.5 family once immediately and then every few seconds, the
	// same cadence the vendor application uses.
	go func() {
		sendDeviceInfoBroadcast(broadcasts, PortUDPApp, opts.Timeout)
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				sendDeviceInfoBroadcast(broadcasts, PortUDPApp, opts.Timeout)
			}
		}
	}()

	listeners.Wait()

	devices := make([]Device, 0, len(found))
	for _, entry := range found {
		device := entry.device
		applyDiscoveryFields(&device, entry.fields)
		devices = append(devices, device)
	}
	if opts.TCPProbe {
		for _, candidate := range probeLocalNetworks(ctx, broadcasts, devices, opts.Timeout) {
			if !containsAddress(devices, candidate.Address) {
				devices = append(devices, candidate)
			}
		}
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].Address < devices[j].Address })
	return devices, nil
}

func applyDiscoveryFields(device *Device, fields map[string]any) {
	if value, ok := fields["gwId"].(string); ok {
		device.ID = value
	}
	if value, ok := fields["id"].(string); ok && device.ID == "" {
		device.ID = value
	}
	if value, ok := fields["productKey"].(string); ok {
		device.ProductKey = value
	}
	if value, ok := fields["mac"].(string); ok && value != "" {
		device.MAC = normalizeMAC(value)
	} else if len(device.ID) == 20 {
		device.MAC = normalizeMAC(device.ID[len(device.ID)-12:])
	}
	switch value := fields["version"].(type) {
	case float64:
		if normalized, err := normalizeVersion(value); err == nil {
			device.Version = normalized
		}
	case string:
		if parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64); err == nil {
			if normalized, err := normalizeVersion(parsed); err == nil {
				device.Version = normalized
			}
		}
	}
	if value, ok := fields["encrypt"].(string); ok && strings.EqualFold(value, "true") {
		device.encrypted = true
	}
}

// normalizeMAC converts the hex blob some firmwares report into a MAC address.
func normalizeMAC(raw string) string {
	clean := strings.ToLower(strings.NewReplacer(":", "", "-", "", " ", "").Replace(raw))
	if len(clean) != 12 {
		return ""
	}
	parts := make([]string, 6)
	for i := 0; i < 6; i++ {
		parts[i] = clean[i*2 : i*2+2]
	}
	return strings.Join(parts, ":")
}

// listenForDevices binds one discovery port and decodes every payload until the
// context is done.
func listenForDevices(ctx context.Context, port int, report func(string, map[string]any)) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: port})
	if err != nil {
		return
	}
	defer conn.Close()
	go func() { <-ctx.Done(); _ = conn.Close() }()
	_ = conn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	buffer := make([]byte, 4096)
	for {
		if ctx.Err() != nil {
			return
		}
		read, address, err := conn.ReadFromUDP(buffer)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			return
		}
		payload, err := decodeUDPPayload(buffer[:read])
		if err != nil {
			continue
		}
		var fields map[string]any
		if json.Unmarshal(payload, &fields) != nil {
			continue
		}
		if from, _ := fields["from"].(string); from == "app" {
			continue
		}
		if _, ok := fields["gwId"]; !ok {
			if _, hasID := fields["id"]; !hasID {
				continue
			}
		}
		report(address.IP.String(), fields)
	}
}

// DecodeAnnouncement unwraps a device announcement captured on the network.
// Devices use three shapes: a classic 55AA frame, a 6699 frame, or a bare
// AES-ECB blob; all three are encrypted with the fixed application key. It is
// exported so an announcement heard by the phone can be read by the backend.
func DecodeAnnouncement(raw []byte) (map[string]any, error) {
	plain, err := decodeUDPPayload(raw)
	if err != nil {
		return nil, err
	}
	var fields map[string]any
	if err := json.Unmarshal(plain, &fields); err != nil {
		return nil, err
	}
	return fields, nil
}

func decodeUDPPayload(raw []byte) ([]byte, error) {
	if len(raw) >= 4 {
		switch {
		case isPrefix(raw, prefix55AA):
			message, err := unpackFrame(raw, 3.3, udpKey[:])
			if err == nil && len(message.payload) > 0 {
				return decodeMaybeEncrypted(message.payload)
			}
		case isPrefix(raw, prefix6699):
			message, err := unpackFrame(raw, 3.5, udpKey[:])
			if err == nil && len(message.payload) > 0 {
				return trimTrailingZeros(message.payload), nil
			}
		}
	}
	plain, err := ecbDecrypt(udpKey[:], raw)
	if err != nil {
		return nil, err
	}
	return trimTrailingZeros(plain), nil
}

func decodeMaybeEncrypted(payload []byte) ([]byte, error) {
	trimmed := trimTrailingZeros(payload)
	if len(trimmed) > 0 && trimmed[0] == '{' {
		return trimmed, nil
	}
	plain, err := ecbDecrypt(udpKey[:], payload)
	if err != nil {
		return nil, err
	}
	return trimTrailingZeros(plain), nil
}

func trimTrailingZeros(raw []byte) []byte {
	for len(raw) > 0 && raw[len(raw)-1] == 0 {
		raw = raw[:len(raw)-1]
	}
	return raw
}

func isPrefix(data []byte, prefix uint32) bool {
	if len(data) < 4 {
		return false
	}
	return data[0] == byte(prefix>>24) && data[1] == byte(prefix>>16) && data[2] == byte(prefix>>8) && data[3] == byte(prefix)
}

// sendDeviceInfoBroadcast asks every device on the network to identify itself.
func sendDeviceInfoBroadcast(addresses []string, port int, timeout time.Duration) {
	local := localAddresses()
	payloads := make([][]byte, 0, 2)
	for _, address := range local {
		body, err := jsonMarshalAppBroadcast(address)
		if err != nil {
			continue
		}
		if message, err := packFrame(0, CmdRequestDevInfo, body, 3.5, udpKey[:]); err == nil {
			payloads = append(payloads, message)
		}
	}
	if len(payloads) == 0 {
		return
	}
	for _, address := range addresses {
		conn, err := net.DialTimeout("udp4", net.JoinHostPort(address, strconv.Itoa(port)), timeout)
		if err != nil {
			continue
		}
		for _, payload := range payloads {
			_, _ = conn.Write(payload)
		}
		_ = conn.Close()
	}
}

// broadcastAddresses lists the broadcast address of every usable IPv4
// interface so multi-homed hosts reach every subnet.
func broadcastAddresses() []string {
	seen := map[string]bool{}
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return []string{"255.255.255.255"}
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			network, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			ip := network.IP.To4()
			if ip == nil {
				continue
			}
			broadcast := make(net.IP, 4)
			for i := 0; i < 4; i++ {
				broadcast[i] = ip[i] | ^network.Mask[i]
			}
			value := broadcast.String()
			if seen[value] {
				continue
			}
			seen[value] = true
			out = append(out, value)
		}
	}
	if len(out) == 0 {
		out = append(out, "255.255.255.255")
	}
	return out
}

func localAddresses() []string {
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			network, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			if ip := network.IP.To4(); ip != nil {
				out = append(out, ip.String())
			}
		}
	}
	return out
}

// probeLocalNetworks looks for the local control port on every subnet this host
// is attached to. It only reports the address: without a local key the device
// does not reveal its identity.
func probeLocalNetworks(ctx context.Context, broadcasts []string, known []Device, timeout time.Duration) []Device {
	networks := map[string]bool{}
	for _, address := range broadcasts {
		parts := strings.Split(address, ".")
		if len(parts) == 4 {
			networks[strings.Join(parts[:3], ".")] = true
		}
	}
	// Skip networks where devices already broadcast.
	for _, device := range known {
		parts := strings.Split(device.Address, ".")
		if len(parts) == 4 {
			delete(networks, strings.Join(parts[:3], "."))
		}
	}
	var mu sync.Mutex
	var found []Device
	var wg sync.WaitGroup
	semaphore := make(chan struct{}, 64)
	for network := range networks {
		for host := 1; host <= 254; host++ {
			if ctx.Err() != nil {
				break
			}
			address := fmt.Sprintf("%s.%d", network, host)
			wg.Add(1)
			semaphore <- struct{}{}
			go func(address string) {
				defer wg.Done()
				defer func() { <-semaphore }()
				conn, err := net.DialTimeout("tcp4", net.JoinHostPort(address, strconv.Itoa(PortTCP)), timeout)
				if err != nil {
					return
				}
				_ = conn.Close()
				mu.Lock()
				found = append(found, Device{Address: address})
				mu.Unlock()
			}(address)
		}
	}
	wg.Wait()
	return found
}

func containsAddress(devices []Device, address string) bool {
	for _, device := range devices {
		if device.Address == address {
			return true
		}
	}
	return false
}

// Identify reads the device status and reports the protocol version that
// actually works, walking the versions from the newest to the oldest. It also
// returns the data points so the caller can derive the device capabilities
// without a second round trip.
func Identify(ctx context.Context, opts Options) (map[string]any, float64, error) {
	candidates := versionLadder(opts.Version)
	var lastErr error
	for _, version := range candidates {
		attempt := opts
		attempt.Version = version
		attempt.Timeout = minDuration(attempt.Timeout, 5*time.Second)
		client, err := Dial(ctx, attempt)
		if err != nil {
			lastErr = err
			continue
		}
		dps, err := client.Status(ctx)
		_ = client.Close()
		if err == nil && len(dps) > 0 {
			return dps, version, nil
		}
		if err != nil {
			lastErr = err
		}
	}
	if lastErr == nil {
		lastErr = ErrNoResponse
	}
	return nil, 0, lastErr
}

// versionLadder orders the versions worth trying for a device, preferring the
// announced one.
func versionLadder(announced float64) []float64 {
	if normalized, err := normalizeVersion(announced); err == nil && announced > 0 {
		switch normalized {
		case 3.1:
			return []float64{3.1, 3.3, 3.4, 3.5}
		case 3.3:
			return []float64{3.3, 3.4, 3.1, 3.5}
		case 3.4:
			return []float64{3.4, 3.3, 3.5, 3.1}
		default:
			return []float64{3.5, 3.4, 3.3, 3.1}
		}
	}
	return []float64{3.3, 3.4, 3.1, 3.5}
}

func minDuration(a, b time.Duration) time.Duration {
	if a <= 0 {
		return b
	}
	if a < b {
		return a
	}
	return b
}

// jsonMarshalAppBroadcast builds the announcement body the vendor application
// sends; kept next to the discovery code so the payload shape stays in one
// place and can be asserted in tests.
func jsonMarshalAppBroadcast(address string) ([]byte, error) {
	return json.Marshal(map[string]string{"from": "app", "ip": address})
}
