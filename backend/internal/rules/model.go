package rules

import "time"

type Actions struct {
	Record bool `json:"record"`
	Notify bool `json:"notify"`
	Alarm  bool `json:"alarm"`
}
type Schedule struct {
	Days     []int  `json:"days"`
	Start    string `json:"start"`
	End      string `json:"end"`
	Timezone string `json:"timezone"`
}
type Rule struct {
	ID              string     `json:"id"`
	CameraID        string     `json:"cameraId"`
	Name            string     `json:"name"`
	DetectorTypes   []string   `json:"detectorTypes"`
	Confirmations   int        `json:"confirmations"`
	CooldownSeconds int        `json:"cooldownSeconds"`
	Schedule        Schedule   `json:"schedule"`
	Actions         Actions    `json:"actions"`
	Enabled         bool       `json:"enabled"`
	LastTriggeredAt *time.Time `json:"lastTriggeredAt,omitempty"`
	CreatedAt       time.Time  `json:"createdAt"`
	UpdatedAt       time.Time  `json:"updatedAt"`
}
type Detection struct {
	CameraID   string
	Type       string
	Confidence float64
	OccurredAt time.Time
	Metadata   map[string]any
}
