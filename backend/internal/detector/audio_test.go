package detector

import "testing"

func TestResidentialAudioCatalogMappings(t *testing.T) {
	cases := map[string]string{
		"Baby cry, infant cry":        "baby_cry",
		"Crying, sobbing":             "crying",
		"Screaming":                   "scream",
		"Glass breaking":              "glass_break",
		"Shatter":                     "glass_break",
		"Smoke detector, smoke alarm": "smoke_alarm",
		"Fire alarm":                  "fire_alarm",
		"Siren":                       "siren",
		"Doorbell":                    "doorbell",
		"Knock":                       "knock",
		"Bark":                        "dog_bark",
	}
	for label, want := range cases {
		if got := AudioLabels[label]; got != want {
			t.Errorf("%q: got %q, want %q", label, got, want)
		}
	}
}

func TestGenericSoundsDoNotBecomeSpecificAlarms(t *testing.T) {
	for _, label := range []string{"Dog", "Glass", "Speech", "Whimper (dog)"} {
		if got, ok := AudioLabels[label]; ok {
			t.Errorf("generic sound %q produced %q", label, got)
		}
	}
}

func TestOneAudioWindowCannotConfirmItself(t *testing.T) {
	got := uniqueResults([]Result{{Type: "glass_break", Confidence: .8}, {Type: "glass_break", Confidence: .9}, {Type: "dog_bark", Confidence: .85}})
	if len(got) != 2 || got[0].Confidence != .9 || got[1].Type != "dog_bark" {
		t.Fatalf("unexpected deduplication: %#v", got)
	}
}
