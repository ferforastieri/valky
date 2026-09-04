package camera

import "time"

type Capabilities struct {
	Snapshot bool `json:"snapshot"`
	Events   bool `json:"events"`
	PTZ      bool `json:"ptz"`
	Zoom     bool `json:"zoom"`
	Presets  bool `json:"presets"`
	Audio    bool `json:"audio"`
}

type ServiceAddresses struct {
	Media  string
	Events string
	PTZ    string
}

type Camera struct {
	ID             string           `json:"id"`
	Name           string           `json:"name"`
	Host           string           `json:"host"`
	Port           int              `json:"port"`
	ProfileToken   string           `json:"profileToken"`
	Capabilities   Capabilities     `json:"capabilities"`
	Services       ServiceAddresses `json:"-"`
	SetupStatus    string           `json:"setupStatus"`
	SetupStep      string           `json:"setupStep"`
	SetupError     string           `json:"setupError,omitempty"`
	SetupUpdatedAt time.Time        `json:"setupUpdatedAt"`
	Enabled        bool             `json:"enabled"`
	CreatedAt      time.Time        `json:"createdAt"`
	UpdatedAt      time.Time        `json:"updatedAt"`
}

type Credentials struct {
	Username string
	Password string
	RTSPURI  string
}

type CreateInput struct {
	Name     string `json:"name"`
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
	RTSPURI  string `json:"rtspUri"`
}

type PTZCommand struct {
	Action string  `json:"action"`
	Pan    float64 `json:"pan"`
	Tilt   float64 `json:"tilt"`
	Zoom   float64 `json:"zoom"`
}
