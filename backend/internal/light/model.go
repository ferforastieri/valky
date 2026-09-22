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
	ID              string       `json:"id"`
	Name            string       `json:"name"`
	Room            string       `json:"room"`
	DeviceID        string       `json:"deviceId"`
	ProtocolVersion float64      `json:"protocolVersion"`
	Capabilities    Capabilities `json:"capabilities"`
	SetupStatus     string       `json:"setupStatus"`
	SetupError      string       `json:"setupError,omitempty"`
	Enabled         bool         `json:"enabled"`
	LastSeenAt      *time.Time   `json:"lastSeenAt,omitempty"`
	State           State        `json:"state"`
	CreatedAt       time.Time    `json:"createdAt"`
	UpdatedAt       time.Time    `json:"updatedAt"`
}

type Credentials struct {
	LocalKey string
	LastIP   string
	Mapping  DPMapping
}

type DPMapping struct {
	Power       int `json:"power"`
	Mode        int `json:"mode"`
	Brightness  int `json:"brightness"`
	Temperature int `json:"temperature"`
	Color       int `json:"color"`
	Scale       int `json:"scale"`
}

func DefaultDPMapping() DPMapping {
	return DPMapping{Power: 20, Mode: 21, Brightness: 22, Temperature: 23, Color: 24, Scale: 1000}
}

type CreateInput struct {
	Name            string  `json:"name"`
	Room            string  `json:"room"`
	DeviceID        string  `json:"deviceId"`
	LocalKey        string  `json:"localKey"`
	IP              string  `json:"ip,omitempty"`
	ProtocolVersion float64 `json:"protocolVersion,omitempty"`
}

type UpdateInput struct {
	Name            string  `json:"name"`
	Room            string  `json:"room"`
	LocalKey        string  `json:"localKey,omitempty"`
	IP              string  `json:"ip,omitempty"`
	ProtocolVersion float64 `json:"protocolVersion,omitempty"`
	Enabled         bool    `json:"enabled"`
}

type StatePatch struct {
	Power             *bool   `json:"power,omitempty"`
	Mode              *string `json:"mode,omitempty"`
	Brightness        *int    `json:"brightness,omitempty"`
	TemperatureKelvin *int    `json:"temperatureKelvin,omitempty"`
	Color             *Color  `json:"color,omitempty"`
}
