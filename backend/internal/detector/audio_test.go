package detector

import "testing"

func TestParseSherpaOutput(t *testing.T) {
	got := ParseOutput(`0: AudioEvent(name="Baby cry, infant cry", index=23, prob=0.912273)`)
	if len(got) != 1 || got[0].Type != "baby_cry" || got[0].Confidence < .91 {
		t.Fatalf("unexpected: %#v", got)
	}
}

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
		output := `AudioEvent(name="` + label + `", index=1, prob=0.81)`
		got := ParseOutput(output)
		if len(got) != 1 || got[0].Type != want {
			t.Errorf("%q: got %#v, want %q", label, got, want)
		}
	}
	if got := ParseOutput(`AudioEvent(name="Speech", index=2, prob=0.99)`); len(got) != 0 {
		t.Fatalf("unlisted negative class generated an event: %#v", got)
	}
}

func TestGenericSoundsDoNotBecomeSpecificAlarms(t *testing.T) {
	for _, label := range []string{"Dog", "Glass", "Speech", "Whimper (dog)"} {
		if got := ParseOutput(`AudioEvent(name="` + label + `", index=1, prob=0.99)`); len(got) != 0 {
			t.Errorf("generic sound %q produced %#v", label, got)
		}
	}
}

func TestOneAudioWindowCannotConfirmItself(t *testing.T) {
	got := uniqueResults([]Result{{Type: "glass_break", Confidence: .8}, {Type: "glass_break", Confidence: .9}, {Type: "dog_bark", Confidence: .85}})
	if len(got) != 2 || got[0].Confidence != .9 || got[1].Type != "dog_bark" {
		t.Fatalf("unexpected deduplication: %#v", got)
	}
}
