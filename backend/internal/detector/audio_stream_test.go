package detector

import (
	"bytes"
	"context"
	"encoding/binary"
	sherpa "github.com/k2-fsa/sherpa-onnx-go/sherpa_onnx"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestAudioWindows(t *testing.T) {
	var pcm bytes.Buffer
	for i := 0; i < 8*audioSampleRate; i++ {
		if err := binary.Write(&pcm, binary.LittleEndian, float32(i)); err != nil {
			t.Fatal(err)
		}
	}
	start := time.Now()
	var windows []audioWindow
	err := readAudioWindows(context.Background(), &pcm, start, "session", func(w audioWindow) error { windows = append(windows, w); return nil })
	if err != io.EOF || len(windows) != 3 {
		t.Fatalf("windows=%d err=%v", len(windows), err)
	}
	for i, w := range windows {
		if len(w.samples) != 4*audioSampleRate || w.samples[0] != float32(i*2*audioSampleRate) || w.samples[len(w.samples)-1] != float32((i*2+4)*audioSampleRate-1) {
			t.Fatalf("window %d: wrong samples or overwritten buffer", i)
		}
		if w.start != start.Add(time.Duration(i*2)*time.Second) || w.end != w.start.Add(4*time.Second) || w.session != "session" {
			t.Fatalf("window %d: wrong timing", i)
		}
	}
	queue := make(chan audioWindow, 1)
	for _, w := range windows {
		offerWindow(queue, w)
	}
	if (<-queue).end != windows[2].end {
		t.Fatal("backlog must retain freshest window")
	}
}

func TestAudioRejectsInvalidPCM(t *testing.T) {
	pcm := make([]byte, audioSampleRate*audioHopSeconds*4)
	binary.LittleEndian.PutUint32(pcm, math.Float32bits(float32(math.NaN())))
	if err := readAudioWindows(context.Background(), bytes.NewReader(pcm), time.Now(), "s", func(audioWindow) error { t.Fatal("invalid window emitted"); return nil }); err == nil {
		t.Fatal("accepted NaN")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := readAudioWindows(ctx, bytes.NewReader(pcm), time.Now(), "s", func(audioWindow) error { return nil }); err != context.Canceled {
		t.Fatal(err)
	}
}

func TestCryEvidence(t *testing.T) {
	cases := []struct {
		name   string
		scores []float64
		want   []bool
	}{
		{"strong", []float64{.9}, []bool{true}},
		{"persistent", []float64{.6, .6, .6, .6, .6}, []bool{false, false, true, true, true}},
		{"interrupted", []float64{.6, .1, .6}, []bool{false, false, false}},
		{"weak", []float64{.2, .2, .2}, []bool{false, false, false}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var g cryEvidence
			for i, score := range tc.scores {
				w := audioWindow{session: "s", start: time.Unix(int64(i*2), 0), end: time.Unix(int64(i*2+4), 0)}
				got, reason := g.evaluate(w, score)
				if got != tc.want[i] {
					t.Fatalf("step %d: %v %s", i, got, reason)
				}
			}
		})
	}
	for _, restart := range []bool{false, true} {
		var g cryEvidence
		w := audioWindow{session: "s", start: time.Unix(0, 0), end: time.Unix(4, 0)}
		g.evaluate(w, .6)
		if got, _ := g.evaluate(w, .9); got {
			t.Fatal("repeated window accepted")
		}
		w.start = w.start.Add(6 * time.Second)
		w.end = w.end.Add(6 * time.Second)
		if restart {
			w.session = "new"
			w.start = time.Unix(4, 0)
			w.end = time.Unix(8, 0)
		}
		if got, _ := g.evaluate(w, .6); got {
			t.Fatal("evidence survived gap/reconnect")
		}
	}
}

// Optional integration test with the checksum-verified upstream model bundle.
// No nursery audio or production notifications are used.
func TestNativeAudioReference(t *testing.T) {
	dir := os.Getenv("VALKYRIS_AUDIO_TEST_MODEL_DIR")
	if dir == "" {
		t.Skip("set VALKYRIS_AUDIO_TEST_MODEL_DIR to the extracted upstream bundle")
	}
	c, err := NewNativeClassifier(filepath.Join(dir, "model.int8.onnx"), filepath.Join(dir, "class_labels_indices.csv"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	files, err := filepath.Glob(filepath.Join(dir, "test_wavs", "*.wav"))
	if err != nil || len(files) == 0 {
		t.Fatalf("reference files: %v", err)
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			wave := sherpa.ReadWave(file)
			if wave == nil || wave.SampleRate != audioSampleRate {
				t.Fatal("reference must be mono 16 kHz")
			}
			if len(wave.Samples) < audioSampleRate*audioWindowSeconds {
				wave.Samples = append(wave.Samples, make([]float32, audioSampleRate*audioWindowSeconds-len(wave.Samples))...)
			}
			var g cryEvidence
			accepted := false
			maxScore := 0.0
			for offset := 0; offset+audioSampleRate*audioWindowSeconds <= len(wave.Samples); offset += audioSampleRate * audioHopSeconds {
				began := time.Now()
				results, err := c.Classify(context.Background(), wave.Samples[offset:offset+audioSampleRate*audioWindowSeconds])
				if err != nil {
					t.Fatal(err)
				}
				scores := map[string]float64{}
				for _, r := range uniqueResults(results) {
					scores[r.Type] = r.Confidence
				}
				w := audioWindow{session: "reference", start: time.Unix(int64(offset/audioSampleRate), 0), end: time.Unix(int64(offset/audioSampleRate+audioWindowSeconds), 0)}
				got, reason := g.evaluate(w, scores["baby_cry"])
				accepted = accepted || got
				maxScore = math.Max(maxScore, scores["baby_cry"])
				t.Logf("offset=%ds baby=%.4f crying=%.4f accepted=%v reason=%s inference=%s", offset/audioSampleRate, scores["baby_cry"], scores["crying"], got, reason, time.Since(began))
			}
			t.Logf("max baby=%.4f accepted=%v", maxScore, accepted)
			if filepath.Base(file) != "6.wav" && accepted {
				t.Fatal("non-baby reference triggered baby cry")
			}
			if filepath.Base(file) == "6.wav" && !accepted {
				t.Fatal("reference baby cry missed")
			}
		})
	}
}

// Validate the actual command against the installed FFmpeg RTSP demuxer.
// Port 1 is intentionally closed: parsing must reach connection establishment.
func TestAudioFFmpegRTSPOptions(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("FFmpeg is not installed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	output, err := audioCommand(ctx, "rtsp://127.0.0.1:1/compatibility-test").CombinedOutput()
	if err == nil || !strings.Contains(string(output), "Connection refused") {
		t.Fatalf("FFmpeg did not reach RTSP connection: %v %s", err, output)
	}
}
