package detector

import (
	"bytes"
	"fmt"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
	"image"
	"image/color"
	_ "image/jpeg"
	"math"
)

// FrameDifference returns the proportion of sampled pixels whose luminance
// changed materially. Sampling every eighth pixel keeps the fallback cheap.
func FrameDifference(before, after []byte) (float64, error) {
	a, _, err := image.Decode(bytes.NewReader(before))
	if err != nil {
		return 0, fmt.Errorf("decode previous frame: %w", err)
	}
	b, _, err := image.Decode(bytes.NewReader(after))
	if err != nil {
		return 0, fmt.Errorf("decode current frame: %w", err)
	}
	bounds := a.Bounds().Intersect(b.Bounds())
	if bounds.Empty() {
		return 0, fmt.Errorf("frames do not overlap")
	}
	var changed, samples int
	for y := bounds.Min.Y; y < bounds.Max.Y; y += 8 {
		for x := bounds.Min.X; x < bounds.Max.X; x += 8 {
			aY := luminance(a.At(x, y))
			bY := luminance(b.At(x, y))
			if abs(aY-bY) > 24 {
				changed++
			}
			samples++
		}
	}
	if samples == 0 {
		return 0, nil
	}
	return float64(changed) / float64(samples), nil
}

func luminance(c color.Color) int {
	r, g, b, _ := c.RGBA()
	return int((299*r + 587*g + 114*b) / 1000 >> 8)
}

func abs(value int) int {
	if value < 0 {
		return -value
	}
	return value
}

// RegionDifference excludes all pixels outside the configured region. Large
// whole-frame changes (IR switching, exposure, camera movement) reset persistence.
func RegionDifference(before, after []byte, region rules.Region) (float64, error) {
	a, _, err := image.Decode(bytes.NewReader(before))
	if err != nil {
		return 0, err
	}
	b, _, err := image.Decode(bytes.NewReader(after))
	if err != nil {
		return 0, err
	}
	if a.Bounds() != b.Bounds() {
		return 0, fmt.Errorf("frame dimensions changed")
	}
	bounds := a.Bounds()
	roi := image.Rect(bounds.Min.X+int(region.X*float64(bounds.Dx())), bounds.Min.Y+int(region.Y*float64(bounds.Dy())), bounds.Min.X+int(math.Ceil((region.X+region.Width)*float64(bounds.Dx()))), bounds.Min.Y+int(math.Ceil((region.Y+region.Height)*float64(bounds.Dy())))).Intersect(bounds)
	if roi.Empty() {
		return 0, fmt.Errorf("empty motion region")
	}
	var all, globalChanged, samples, changed int
	for y := bounds.Min.Y; y < bounds.Max.Y; y += 4 {
		for x := bounds.Min.X; x < bounds.Max.X; x += 4 {
			different := abs(luminance(a.At(x, y))-luminance(b.At(x, y))) > 24
			all++
			if different {
				globalChanged++
			}
			if image.Pt(x, y).In(roi) {
				samples++
				if different {
					changed++
				}
			}
		}
	}
	if all == 0 || samples == 0 {
		return 0, fmt.Errorf("insufficient motion samples")
	}
	if float64(globalChanged)/float64(all) >= .8 {
		return 0, nil
	}
	return float64(changed) / float64(samples), nil
}
