package detector

import (
	"bufio"
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

type Result struct {
	Type       string  `json:"type"`
	Confidence float64 `json:"confidence"`
}
type AudioClassifier interface {
	Classify(context.Context, string) ([]Result, error)
}
type SherpaCLI struct{ Binary, Model, Labels string }

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

var outputPattern = regexp.MustCompile(`AudioEvent\(name="([^"]+)", index=\d+, prob=([0-9.]+)\)`)

func (s SherpaCLI) Classify(ctx context.Context, wav string) ([]Result, error) {
	if s.Binary == "" {
		s.Binary = "sherpa-onnx-offline-audio-tagging"
	}
	cmd := exec.CommandContext(ctx, s.Binary, "--zipformer-model="+s.Model, "--labels="+s.Labels, wav)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("audio classifier: %w", err)
	}
	return ParseOutput(string(output)), nil
}
func ParseOutput(output string) []Result {
	var results []Result
	scanner := bufio.NewScanner(strings.NewReader(output))
	for scanner.Scan() {
		match := outputPattern.FindStringSubmatch(scanner.Text())
		if len(match) != 3 {
			continue
		}
		kind, ok := AudioLabels[match[1]]
		if !ok {
			continue
		}
		confidence, _ := strconv.ParseFloat(match[2], 64)
		results = append(results, Result{Type: kind, Confidence: confidence})
	}
	return results
}
