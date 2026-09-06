package detector

import (
	"bytes"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
	"image"
	"image/color"
	"image/png"
	"testing"
)

func visualFrame(t *testing.T, rect image.Rectangle) []byte {
	t.Helper()
	img := image.NewGray(image.Rect(0, 0, 160, 160))
	for y := rect.Min.Y; y < rect.Max.Y; y++ {
		for x := rect.Min.X; x < rect.Max.X; x++ {
			img.Set(x, y, color.White)
		}
	}
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}
func TestRegionDifferenceIgnoresOutsideAndLighting(t *testing.T) {
	before := visualFrame(t, image.Rectangle{})
	region := rules.Region{X: .25, Y: .25, Width: .5, Height: .5}
	for _, v := range []struct {
		name   string
		rect   image.Rectangle
		motion bool
	}{{"outside", image.Rect(0, 0, 30, 30), false}, {"inside", image.Rect(50, 50, 80, 80), true}, {"IR switch", image.Rect(0, 0, 160, 160), false}, {"quiet", image.Rectangle{}, false}} {
		t.Run(v.name, func(t *testing.T) {
			score, err := RegionDifference(before, visualFrame(t, v.rect), region)
			if err != nil {
				t.Fatal(err)
			}
			if (score > .05) != v.motion {
				t.Fatalf("score %f", score)
			}
		})
	}
	if _, err := RegionDifference([]byte("invalid"), before, region); err == nil {
		t.Fatal("accepted corrupt frame")
	}
}
