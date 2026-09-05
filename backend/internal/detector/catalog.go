package detector

type Kind struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	Source string `json:"source"`
}

var Catalog = []Kind{
	{ID: "motion", Label: "Motion", Source: "onvif_or_video"}, {ID: "person", Label: "Person", Source: "onvif"}, {ID: "tamper", Label: "Camera tampering", Source: "onvif"},
	{ID: "baby_cry", Label: "Baby cry", Source: "audio"}, {ID: "crying", Label: "Crying or sobbing", Source: "audio"}, {ID: "scream", Label: "Scream", Source: "audio"}, {ID: "glass_break", Label: "Glass breaking", Source: "audio"}, {ID: "smoke_alarm", Label: "Smoke alarm", Source: "audio"}, {ID: "fire_alarm", Label: "Fire alarm", Source: "audio"}, {ID: "siren", Label: "Siren", Source: "audio"}, {ID: "doorbell", Label: "Doorbell", Source: "audio"}, {ID: "knock", Label: "Knock", Source: "audio"}, {ID: "dog_bark", Label: "Dog bark", Source: "audio"},
}

var AudioLabels = map[string]string{
	"Baby cry, infant cry": "baby_cry", "Crying, sobbing": "crying", "Screaming": "scream", "Shatter": "glass_break", "Glass breaking": "glass_break", "Smoke detector, smoke alarm": "smoke_alarm", "Fire alarm": "fire_alarm", "Siren": "siren", "Doorbell": "doorbell", "Knock": "knock", "Bark": "dog_bark",
}
