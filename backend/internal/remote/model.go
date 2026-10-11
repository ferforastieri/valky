package remote

import "time"

// KindSamsung identifies the Samsung Tizen WebSocket remote protocol.
const KindSamsung = "samsung"

// DefaultPort is the secure Tizen remote channel. Port 8001 is the legacy
// unencrypted channel used by some pre-2016 models.
const (
	DefaultPort = 8002
	LegacyPort  = 8001
)

// Device is a controllable appliance (currently a Samsung TV). The access token
// issued by the TV is never part of the public model: only whether one exists.
type Device struct {
	ID              string     `json:"id"`
	Name            string     `json:"name"`
	Kind            string     `json:"kind"`
	Host            string     `json:"host"`
	Port            int        `json:"port"`
	SetupStatus     string     `json:"setupStatus"`
	SetupError      string     `json:"setupError,omitempty"`
	TokenConfigured bool       `json:"tokenConfigured"`
	Enabled         bool       `json:"enabled"`
	LastSeenAt      *time.Time `json:"lastSeenAt,omitempty"`
	CreatedAt       time.Time  `json:"createdAt"`
	UpdatedAt       time.Time  `json:"updatedAt"`
}

// Credentials carries the decrypted token to the protocol driver.
type Credentials struct {
	Address string
	Token   string
}

type CreateInput struct {
	Name string `json:"name"`
	Kind string `json:"kind"`
	Host string `json:"host"`
	Port int    `json:"port"`
}

type UpdateInput struct {
	Name    *string `json:"name,omitempty"`
	Host    *string `json:"host,omitempty"`
	Port    *int    `json:"port,omitempty"`
	Enabled *bool   `json:"enabled,omitempty"`
}

type KeyInput struct {
	Key string `json:"key"`
}

// AllowedKeys is the whitelist of Samsung remote keys accepted by the API.
// Restricting the set keeps the backend from forwarding arbitrary payloads to
// the TV while still covering the common navigation, volume and playback keys.
var AllowedKeys = map[string]struct{}{
	"KEY_POWER": {}, "KEY_UP": {}, "KEY_DOWN": {}, "KEY_LEFT": {}, "KEY_RIGHT": {},
	"KEY_ENTER": {}, "KEY_RETURN": {}, "KEY_HOME": {}, "KEY_MENU": {}, "KEY_EXIT": {},
	"KEY_VOLUP": {}, "KEY_VOLDOWN": {}, "KEY_MUTE": {},
	"KEY_CHUP": {}, "KEY_CHDOWN": {}, "KEY_CH_LIST": {}, "KEY_PRECH": {},
	"KEY_PLAY": {}, "KEY_PAUSE": {}, "KEY_STOP": {}, "KEY_REWIND": {}, "KEY_FF": {},
	"KEY_REC": {}, "KEY_INFO": {}, "KEY_GUIDE": {}, "KEY_TOOLS": {}, "KEY_SOURCE": {},
	"KEY_TV": {}, "KEY_HDMI": {},
	"KEY_0": {}, "KEY_1": {}, "KEY_2": {}, "KEY_3": {}, "KEY_4": {},
	"KEY_5": {}, "KEY_6": {}, "KEY_7": {}, "KEY_8": {}, "KEY_9": {},
	"KEY_RED": {}, "KEY_GREEN": {}, "KEY_YELLOW": {}, "KEY_BLUE": {},
}

func validKey(key string) bool {
	_, ok := AllowedKeys[key]
	return ok
}
