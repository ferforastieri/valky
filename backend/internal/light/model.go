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
	Room         string       `json:"room"`
	Capabilities Capabilities `json:"capabilities"`
	SetupStatus  string       `json:"setupStatus"`
	SetupError   string       `json:"setupError,omitempty"`
	Enabled      bool         `json:"enabled"`
	LastSeenAt   *time.Time   `json:"lastSeenAt,omitempty"`
	State        State        `json:"state"`
	CreatedAt    time.Time    `json:"createdAt"`
	UpdatedAt    time.Time    `json:"updatedAt"`
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
