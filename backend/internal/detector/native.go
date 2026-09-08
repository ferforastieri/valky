package detector

import (
	"context"
	"fmt"
	"sync"

	sherpa "github.com/k2-fsa/sherpa-onnx-go/sherpa_onnx"
)

// NativeClassifier keeps the int8 Zipformer model loaded in-process. The
// sherpa-onnx Go package calls the native C API through CGO and ships Linux
// libraries for both amd64 and arm64.
type NativeClassifier struct {
	mu     sync.Mutex
	tagger *sherpa.AudioTagging
}

func NewNativeClassifier(model, labels string) (*NativeClassifier, error) {
	config := &sherpa.AudioTaggingConfig{
		Model: sherpa.AudioTaggingModelConfig{
			Zipformer:  sherpa.OfflineZipformerAudioTaggingModelConfig{Model: model},
			NumThreads: 2,
			Provider:   "cpu",
		},
		Labels: labels,
		TopK:   527,
	}
	tagger := sherpa.NewAudioTagging(config)
	if tagger == nil {
		return nil, fmt.Errorf("initialize sherpa-onnx audio tagging")
	}
	return &NativeClassifier{tagger: tagger}, nil
}

func (c *NativeClassifier) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.tagger != nil {
		sherpa.DeleteAudioTagging(c.tagger)
		c.tagger = nil
	}
}

func (c *NativeClassifier) Classify(ctx context.Context, samples []float32) ([]Result, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if len(samples) == 0 {
		return nil, fmt.Errorf("empty audio window")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if c.tagger == nil {
		return nil, fmt.Errorf("audio classifier is closed")
	}
	stream := sherpa.NewAudioTaggingStream(c.tagger)
	defer sherpa.DeleteOfflineStream(stream)
	stream.AcceptWaveform(audioSampleRate, samples)
	events := c.tagger.Compute(stream, 527)
	results := make([]Result, 0, len(events))
	for _, candidate := range events {
		kind, ok := AudioLabels[candidate.Name]
		if ok {
			results = append(results, Result{Type: kind, Confidence: float64(candidate.Prob)})
		}
	}
	return results, nil
}
