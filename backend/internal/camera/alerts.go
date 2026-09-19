package camera

import (
	"encoding/json"
	"fmt"
	"unicode/utf8"
)

// Empty text uses the localized Android defaults. Settings apply to all
// recipients; rule actions still decide whether an event triggers an alarm.
type AlertPresentation struct {
	NotificationTitle string `json:"notificationTitle"`
	NotificationBody  string `json:"notificationBody"`
	AlarmTitle        string `json:"alarmTitle"`
	AlarmBody         string `json:"alarmBody"`
	AlarmSound        string `json:"alarmSound"`
	Vibrate           bool   `json:"vibrate"`
	FullScreen        bool   `json:"fullScreen"`
}

func DefaultAlertPresentation() AlertPresentation {
	return AlertPresentation{AlarmSound: "alarm", Vibrate: true, FullScreen: true}
}

// Android omits default-valued fields; absent fields must retain the same defaults.
func (a *AlertPresentation) UnmarshalJSON(data []byte) error {
	type plain AlertPresentation
	defaults := plain(DefaultAlertPresentation())
	if err := json.Unmarshal(data, &defaults); err != nil {
		return err
	}
	*a = AlertPresentation(defaults)
	return nil
}

func (a AlertPresentation) Validate() error {
	if len(a.NotificationTitle)+len(a.NotificationBody)+len(a.AlarmTitle)+len(a.AlarmBody) > 1600 {
		return fmt.Errorf("combined alert text must not exceed 1600 UTF-8 bytes")
	}
	for _, title := range []string{a.NotificationTitle, a.AlarmTitle} {
		if utf8.RuneCountInString(title) > 80 {
			return fmt.Errorf("alert titles must not exceed 80 characters")
		}
	}
	for _, body := range []string{a.NotificationBody, a.AlarmBody} {
		if utf8.RuneCountInString(body) > 240 {
			return fmt.Errorf("alert messages must not exceed 240 characters")
		}
	}
	switch a.AlarmSound {
	case "alarm", "ringtone", "silent":
		return nil
	default:
		return fmt.Errorf("alarmSound must be alarm, ringtone or silent")
	}
}
