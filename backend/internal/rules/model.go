package rules

import "time"

type Actions struct {
	RecipientUserIDs []string `json:"recipientUserIds"`
	Record           bool     `json:"record"`
	Notify           bool     `json:"notify"`
	Alarm            bool     `json:"alarm"`
}
type Schedule struct {
	Days     []int  `json:"days"`
	Start    string `json:"start"`
	End      string `json:"end"`
	Timezone string `json:"timezone"`
}

// Region uses normalized image coordinates, independent of stream resolution.
type Region struct {
	X      float64 `json:"x"`
	Y      float64 `json:"y"`
	Width  float64 `json:"width"`
	Height float64 `json:"height"`
}
type MotionSettings struct {
	Region             Region  `json:"region"`
	MinDurationSeconds int     `json:"minDurationSeconds"`
	MinChangedFraction float64 `json:"minChangedFraction"`
}

// MotionSample is internal-only: generic camera events cannot satisfy a region rule.
type MotionSample struct {
	RuleID          string
	RuleUpdatedAt   time.Time
	ChangedFraction float64
}
type Rule struct {
	Motion          *MotionSettings `json:"motion,omitempty"`
	ID              string          `json:"id"`
	CameraID        string          `json:"cameraId"`
	Name            string          `json:"name"`
	DetectorTypes   []string        `json:"detectorTypes"`
	Confirmations   int             `json:"confirmations"`
	CooldownSeconds int             `json:"cooldownSeconds"`
	Schedule        Schedule        `json:"schedule"`
	Actions         Actions         `json:"actions"`
	Enabled         bool            `json:"enabled"`
	LastTriggeredAt *time.Time      `json:"lastTriggeredAt,omitempty"`
	CreatedAt       time.Time       `json:"createdAt"`
	UpdatedAt       time.Time       `json:"updatedAt"`
}
type AudioSample struct {
	Start            time.Time
	End              time.Time
	Session          string
	TemporalAccepted bool
}

type Detection struct {
	Audio      *AudioSample  `json:"-"`
	Motion     *MotionSample `json:"-"`
	CameraID   string
	Type       string
	Confidence float64
	OccurredAt time.Time
	Metadata   map[string]any
}
