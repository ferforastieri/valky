package detector

import "context"

type Result struct {
	Type       string  `json:"type"`
	Confidence float64 `json:"confidence"`
}
type AudioClassifier interface {
	Classify(context.Context, []float32) ([]Result, error)
}

// Multiple model labels can describe the same event. A single audio window
// must never count as multiple confirmations of that event.
func uniqueResults(results []Result) []Result {
	positions := make(map[string]int)
	out := make([]Result, 0, len(results))
	for _, result := range results {
		if index, ok := positions[result.Type]; ok {
			if result.Confidence > out[index].Confidence {
				out[index] = result
			}
		} else {
			positions[result.Type] = len(out)
			out = append(out, result)
		}
	}
	return out
}
