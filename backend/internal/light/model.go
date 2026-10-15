package light

import "time"

type Capabilities struct {
	Brightness       bool `json:"brightness"`
	Color            bool `json:"color"`
	ColorTemperature bool `json:"colorTemperature"`
}

type Color struct {
	Hue        int `json:"hue"`
	Saturation int `json:"saturation"`
	Value      int `json:"value"`
}

type State struct {
	Online            bool   `json:"online"`
	Power             bool   `json:"power"`
	Mode              string `json:"mode"`
	Brightness        int    `json:"brightness"`
	TemperatureKelvin int    `json:"temperatureKelvin"`
	Color             Color  `json:"color"`
	UpdatedAt         string `json:"updatedAt,omitempty"`
}

type Light struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	Capabilities Capabilities `json:"capabilities"`
	SetupStatus  string       `json:"setupStatus"`
	SetupError   string       `json:"setupError,omitempty"`
	Enabled      bool         `json:"enabled"`
	Favorite     bool         `json:"favorite"`
	LastSeenAt   *time.Time   `json:"lastSeenAt,omitempty"`
	State        State        `json:"state"`
	CreatedAt    time.Time    `json:"createdAt"`
	UpdatedAt    time.Time    `json:"updatedAt"`

	// LastAddress is the address that worked for this light the last time it
	// answered, used as the first candidate when the device hides itself from
	// the discovery broadcast.
	LastAddress string `json:"lastAddress,omitempty"`
	// DeviceID is the identifier the device announces on the local network.
	DeviceID string `json:"deviceId,omitempty"`
	// ProtocolVersion is the local protocol version the device speaks. It is
	// detected while pairing and can be corrected by the administrator.
	ProtocolVersion float64 `json:"protocolVersion,omitempty"`
	// DPMapping is the internal data point layout of the device. It is never
	// sent to clients: they only see the capabilities it produces.
	DPMapping string `json:"-"`
}

type Credentials struct {
	Secret  string
	Address string
}

type StatePatch struct {
	Power             *bool   `json:"power,omitempty"`
	Mode              *string `json:"mode,omitempty"`
	Brightness        *int    `json:"brightness,omitempty"`
	TemperatureKelvin *int    `json:"temperatureKelvin,omitempty"`
	Color             *Color  `json:"color,omitempty"`
}

// UpdateInput changes what an administrator can edit after pairing: the name,
// the room and whether Valkyris keeps polling the lamp. The local credentials
// belong to the pairing flow and are never edited by hand.
type UpdateInput struct {
	Name    *string `json:"name,omitempty"`
	Enabled *bool   `json:"enabled,omitempty"`
}

// DiscoveryInput configures a local network scan for new lights.
type DiscoveryInput struct {
	// Duration is how long to listen for device announcements, in seconds.
	Duration int `json:"duration,omitempty"`
	// Probe also tries the control port on every address of the local subnets.
	Probe bool `json:"probe,omitempty"`
}

// RegisterInput adopts a lamp that another application already configured. The
// administrator supplies what the local protocol needs — the address, the device
// identifier and the 16 character local key — and Valkyris validates them
// against the device before storing anything.
type RegisterInput struct {
	Name            string  `json:"name,omitempty"`
	Address         string  `json:"address"`
	DeviceID        string  `json:"deviceId"`
	LocalKey        string  `json:"localKey"`
	ProtocolVersion float64 `json:"protocolVersion,omitempty"`
}
