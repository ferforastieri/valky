package light

import (
	"testing"
)

func TestDetectProfileReadsTheReportedDataPoints(t *testing.T) {
	cases := []struct {
		name string
		dps  map[string]any
		want string
	}{
		{"modern firmware", map[string]any{"20": true, "21": "white", "22": 500, "23": 300, "24": "00f003e8"}, modernProfile.Name},
		{"legacy firmware", map[string]any{"1": true, "2": "white", "3": 120, "4": 90, "5": "ff8000"}, legacyProfile.Name},
		{"white only", map[string]any{"1": true, "2": 120, "3": 90}, whiteProfile.Name},
		{"unknown", map[string]any{"101": true}, modernProfile.Name},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := detectProfile(testCase.dps).Name; got != testCase.want {
				t.Fatalf("detectProfile = %q, want %q", got, testCase.want)
			}
		})
	}
}

func TestCapabilitiesFollowTheProfile(t *testing.T) {
	if caps := modernProfile.capabilities(); !caps.Brightness || !caps.Color || !caps.ColorTemperature {
		t.Fatalf("modern profile capabilities = %+v", caps)
	}
	if caps := whiteProfile.capabilities(); caps.Color || !caps.Brightness || !caps.ColorTemperature {
		t.Fatalf("white profile capabilities = %+v", caps)
	}
}

func TestBrightnessConversionRoundTrips(t *testing.T) {
	for _, profile := range []DPProfile{modernProfile, legacyProfile} {
		for percent := 0; percent <= 100; percent += 5 {
			raw := percentToRaw(percent, profile.BrightnessMin, profile.BrightnessMax)
			if raw < profile.BrightnessMin || raw > profile.BrightnessMax {
				t.Fatalf("%s: raw %d out of range for %d%%", profile.Name, raw, percent)
			}
			back := rawToPercent(raw, profile.BrightnessMin, profile.BrightnessMax)
			if back < percent-1 || back > percent+1 {
				t.Fatalf("%s: %d%% came back as %d%% (raw %d)", profile.Name, percent, back, raw)
			}
		}
	}
}

func TestKelvinConversionStaysInsideTheRange(t *testing.T) {
	for _, profile := range []DPProfile{modernProfile, legacyProfile} {
		warm := kelvinToRaw(WarmestKelvin, profile.TemperatureMin, profile.TemperatureMax)
		cool := kelvinToRaw(CoolestKelvin, profile.TemperatureMin, profile.TemperatureMax)
		if warm >= cool {
			t.Fatalf("%s: the warmest temperature should map below the coolest", profile.Name)
		}
		mid := rawToKelvin(percentToRaw(50, profile.TemperatureMin, profile.TemperatureMax), profile.TemperatureMin, profile.TemperatureMax)
		if mid < WarmestKelvin || mid > CoolestKelvin {
			t.Fatalf("%s: mid temperature out of range: %d", profile.Name, mid)
		}
	}
}

func TestColourEncodingMatchesTheFirmwareFormats(t *testing.T) {
	// hsv16: hue in degrees, saturation and value scaled to 1000, all in hex.
	if got := modernProfile.encodeColour(Color{Hue: 360, Saturation: 100, Value: 100}); got != "016803e803e8" {
		t.Fatalf("modern colour = %q", got)
	}
	if got := modernProfile.encodeColour(Color{Hue: 0, Saturation: 0, Value: 100}); got != "0000000003e8" {
		t.Fatalf("modern colour = %q", got)
	}
	// rgb8: the firmware expects plain RGB hex.
	if got := legacyProfile.encodeColour(Color{Hue: 0, Saturation: 100, Value: 100}); got != "ff0000" {
		t.Fatalf("legacy colour = %q", got)
	}
	if got := legacyProfile.encodeColour(Color{Hue: 120, Saturation: 100, Value: 100}); got != "00ff00" {
		t.Fatalf("legacy colour = %q", got)
	}
}

func TestColourDecodingRoundTrips(t *testing.T) {
	original := Color{Hue: 200, Saturation: 80, Value: 60}
	encoded := modernProfile.encodeColour(original)
	decoded := modernProfile.decodeColour(encoded, Color{})
	if difference := original.Hue - decoded.Hue; difference > 1 || difference < -1 {
		t.Fatalf("modern hue came back as %d, want %d", decoded.Hue, original.Hue)
	}
	if difference := original.Saturation - decoded.Saturation; difference > 1 || difference < -1 {
		t.Fatalf("modern saturation came back as %d, want %d", decoded.Saturation, original.Saturation)
	}
	if difference := original.Value - decoded.Value; difference > 1 || difference < -1 {
		t.Fatalf("modern value came back as %d, want %d", decoded.Value, original.Value)
	}
	legacyRGB := legacyProfile.encodeColour(Color{Hue: 240, Saturation: 100, Value: 100})
	decodedLegacy := legacyProfile.decodeColour(legacyRGB, Color{})
	if decodedLegacy.Hue < 235 || decodedLegacy.Hue > 245 {
		t.Fatalf("legacy hue came back as %d", decodedLegacy.Hue)
	}
	// Garbage keeps the previous value instead of resetting the colour.
	if kept := modernProfile.decodeColour("zzzz", Color{Hue: 10, Saturation: 20, Value: 30}); kept.Hue != 10 {
		t.Fatalf("invalid colour replaced the current value: %+v", kept)
	}
}

func TestStateFromDataPoints(t *testing.T) {
	dps := map[string]any{
		"20": true,
		"21": "colour",
		"22": float64(500),
		"23": float64(0),
		"24": "00f003e803e8",
	}
	state := modernProfile.stateFromDPS(dps, State{})
	if !state.Power {
		t.Fatal("power was not read")
	}
	if state.Mode != neutralModeColor {
		t.Fatalf("mode = %q", state.Mode)
	}
	if state.Brightness != 49 && state.Brightness != 50 {
		t.Fatalf("brightness = %d", state.Brightness)
	}
	if state.TemperatureKelvin != WarmestKelvin {
		t.Fatalf("temperature = %d", state.TemperatureKelvin)
	}
	if state.Color.Hue != 240 || state.Color.Saturation != 100 || state.Color.Value != 100 {
		t.Fatalf("colour = %+v", state.Color)
	}
}

func TestPatchToDataPointsKeepsTheDeviceInSync(t *testing.T) {
	on := true
	brightness := 40
	kelvin := 5000
	colour := Color{Hue: 300, Saturation: 90, Value: 70}
	colourMode := neutralModeColor

	// Writing only the power must not disturb brightness or mode.
	dps := modernProfile.patchToDPS(StatePatch{Power: &on}, map[string]any{"20": false, "22": 500})
	if len(dps) != 1 || dps["20"] != true {
		t.Fatalf("power patch produced %+v", dps)
	}

	// Brightness keeps the current mode.
	dps = modernProfile.patchToDPS(StatePatch{Brightness: &brightness}, map[string]any{"21": "white", "22": 500})
	if raw, ok := dps["22"].(int); !ok || raw != percentToRaw(brightness, modernProfile.BrightnessMin, modernProfile.BrightnessMax) {
		t.Fatalf("brightness patch produced %+v", dps)
	}
	if _, hasMode := dps["21"]; hasMode {
		t.Fatalf("brightness patch changed the mode: %+v", dps)
	}

	// A colour always selects colour mode, like the vendor application does.
	dps = modernProfile.patchToDPS(StatePatch{Color: &colour}, map[string]any{"21": "white"})
	if dps["21"] != deviceModeColour {
		t.Fatalf("colour patch did not select colour mode: %+v", dps)
	}
	if dps["24"] != modernProfile.encodeColour(colour) {
		t.Fatalf("colour patch produced %v", dps["24"])
	}

	// A temperature always selects white mode.
	dps = modernProfile.patchToDPS(StatePatch{TemperatureKelvin: &kelvin}, map[string]any{"21": "colour"})
	if dps["21"] != deviceModeWhite {
		t.Fatalf("temperature patch did not select white mode: %+v", dps)
	}
	if raw, ok := dps["23"].(int); !ok || raw <= modernProfile.TemperatureMin {
		t.Fatalf("temperature patch produced %+v", dps)
	}

	// A white-only bulb ignores colour instructions instead of failing.
	if dps = whiteProfile.patchToDPS(StatePatch{Color: &colour}, nil); len(dps) != 0 {
		t.Fatalf("a white-only bulb accepted a colour: %+v", dps)
	}
	if dps = whiteProfile.patchToDPS(StatePatch{Mode: &colourMode}, nil); len(dps) != 0 {
		t.Fatalf("a white-only bulb accepted a mode: %+v", dps)
	}
}

func TestProfileEncodeDecodeRoundTrip(t *testing.T) {
	encoded := legacyProfile.encode()
	decoded, ok := decodeProfile(encoded)
	if !ok {
		t.Fatalf("profile did not decode: %s", encoded)
	}
	if decoded.Name != legacyProfile.Name || decoded.Colour != legacyProfile.Colour || decoded.BrightnessMax != legacyProfile.BrightnessMax {
		t.Fatalf("decoded profile = %+v", decoded)
	}
	if _, ok := decodeProfile("{}"); ok {
		t.Fatal("an empty mapping decoded as a profile")
	}
	if _, ok := decodeProfile("not json"); ok {
		t.Fatal("invalid json decoded as a profile")
	}
}
