package detector

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"os/exec"
	"time"

	"github.com/ferforastieri/valky/backend/internal/rules"
	"github.com/google/uuid"
)

const audioSampleRate = 16000
const audioWindowSeconds = 4
const audioHopSeconds = 2

// A tosse é curta e fica diluída na janela de 4 s. Quando a pontuação da janela
// inteira é baixa mas ainda plausível, uma segunda passada em janelas de 2 s
// recupera o transiente sem aumentar o custo dos trechos silenciosos.
const coughRefinementWindowSeconds = 2
const coughRefinementFloor = .05
const coughRefinementThreshold = .93

type audioWindow struct {
	samples    []float32
	start, end time.Time
	session    string
}

// The capture reader never waits for inference. At most one fresh window waits;
// overload drops old analysis windows, not the live capture connection.
func offerWindow(queue chan audioWindow, w audioWindow) {
	select {
	case queue <- w:
		return
	default:
	}
	select {
	case <-queue:
	default:
	}
	select {
	case queue <- w:
	default:
	}
}

func readAudioWindows(ctx context.Context, input io.Reader, start time.Time, session string, emit func(audioWindow) error) error {
	hop := make([]byte, audioSampleRate*audioHopSeconds*4)
	samples := make([]float32, 0, audioSampleRate*audioWindowSeconds)
	frames := int64(0)
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if _, err := io.ReadFull(input, hop); err != nil {
			return err
		}
		if len(samples) == cap(samples) {
			copy(samples, samples[audioSampleRate*audioHopSeconds:])
			samples = samples[:audioSampleRate*(audioWindowSeconds-audioHopSeconds)]
		}
		for i := 0; i < len(hop); i += 4 {
			value := math.Float32frombits(binary.LittleEndian.Uint32(hop[i : i+4]))
			if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
				return fmt.Errorf("invalid PCM sample")
			}
			samples = append(samples, value)
		}
		frames += int64(audioSampleRate * audioHopSeconds)
		if len(samples) == cap(samples) {
			end := start.Add(time.Duration(frames) * time.Second / audioSampleRate)
			if err := emit(audioWindow{samples: append([]float32(nil), samples...), start: end.Add(-audioWindowSeconds * time.Second), end: end, session: session}); err != nil {
				return err
			}
		}
	}
}

func audioCommand(ctx context.Context, input string) *exec.Cmd {
	// Keep the RTSP session open. TCP avoids silent holes caused by UDP loss;
	// timeout bounds an unresponsive camera without imposing a stream lifetime.
	return exec.CommandContext(ctx, "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-rtsp_transport", "tcp", "-timeout", "15000000", "-i", input, "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1")
}

func captureAudioStream(ctx context.Context, input string, queue chan audioWindow) error {
	cmd := audioCommand(ctx, input)
	output, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("open audio pipe: %w", err)
	}
	// Do not include ffmpeg stderr: it can contain camera URLs or credentials.
	if err = cmd.Start(); err != nil {
		return fmt.Errorf("start audio capture: %w", err)
	}
	session := uuid.NewString()
	err = readAudioWindows(ctx, output, time.Now().UTC(), session, func(w audioWindow) error {
		if time.Since(w.end) > 8*time.Second {
			return fmt.Errorf("audio capture exceeded latency budget")
		}
		offerWindow(queue, w)
		return nil
	})
	_ = cmd.Process.Kill()
	waitErr := cmd.Wait()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return fmt.Errorf("audio stream stopped: read=%v process=%v", err, waitErr)
}

func (m *Monitor) monitorAudio(ctx context.Context, cameraID string) {
	queue := make(chan audioWindow, 1)
	done := make(chan struct{})
	go func() { defer close(done); m.classifyAudio(ctx, cameraID, queue) }()
	defer func() { close(queue); <-done }()
	for ctx.Err() == nil {
		m.Logger.Info("audio capture started", "camera", cameraID, "windowSeconds", audioWindowSeconds, "hopSeconds", audioHopSeconds)
		err := captureAudioStream(ctx, m.Media.RTSPURL(cameraID), queue)
		if ctx.Err() != nil {
			return
		}
		m.Logger.Warn("audio capture interrupted", "camera", cameraID, "error", err)
		m.recordAudio(ctx, AudioDecision{CameraID: cameraID, End: time.Now().UTC(), Reason: "capture_interrupted"})
		if !wait(ctx, 2*time.Second) {
			return
		}
	}
}

func (m *Monitor) classifyAudio(ctx context.Context, cameraID string, queue <-chan audioWindow) {
	var gate cryEvidence
	previousReady := map[string]bool{}
	for {
		select {
		case <-ctx.Done():
			return
		case window, ok := <-queue:
			if !ok {
				return
			}
			decision := AudioDecision{CameraID: cameraID, Start: window.start, End: window.end, Session: window.session}
			if time.Since(window.end) > 8*time.Second {
				gate = cryEvidence{}
				decision.Reason = "analysis_lag"
				m.recordAudio(ctx, decision)
				continue
			}
			began := time.Now()
			results, err := m.Classifier.Classify(ctx, window.samples)
			decision.InferenceMillis = time.Since(began).Milliseconds()
			if err != nil {
				gate = cryEvidence{}
				if ctx.Err() != nil {
					return
				}
				decision.Reason = "classification_failed"
				m.Logger.Warn("classify audio", "camera", cameraID, "error", err)
				m.recordAudio(ctx, decision)
				continue
			}
			scores := map[string]float64{}
			for _, result := range uniqueResults(results) {
				scores[result.Type] = result.Confidence
			}
			decision.Scores = scores
			fullCough := scores["cough"]
			coughScore := fullCough
			if refined, ok := m.refineCough(ctx, cameraID, window.samples, fullCough); ok {
				coughScore = refined
				scores["cough"] = refined
			}
			var sum float64
			for _, v := range window.samples {
				sum += float64(v) * float64(v)
			}
			decision.RMS = math.Sqrt(sum / float64(len(window.samples)))
			accepted, reason := gate.evaluate(window, scores["baby_cry"])
			decision.Reason = reason
			decision.Accepted = accepted
			for kind, score := range scores {
				ready := score >= rules.AudioThreshold(kind)
				if kind == "baby_cry" {
					ready = accepted
				}
				wasReady := previousReady[kind]
				previousReady[kind] = ready
				if !ready && !wasReady {
					continue
				}
				metadata := map[string]any{"source": "sherpa-onnx", "audioWindowStart": window.start, "audioWindowEnd": window.end, "audioDecision": reason, "babyCryScore": scores["baby_cry"], "cryingScore": scores["crying"]}
				if kind == "cough" && coughScore != fullCough {
					metadata["coughFullScore"] = fullCough
					metadata["coughWindowScore"] = coughScore
					metadata["coughRefined"] = true
				}
				m.submit(ctx, rules.Detection{Audio: &rules.AudioSample{Start: window.start, End: window.end, Session: window.session, TemporalAccepted: ready}, CameraID: cameraID, Type: kind, Confidence: score, OccurredAt: window.end, Metadata: metadata})
			}
			if coughScore != fullCough {
				// O diagnóstico mantém as duas pontuações: a janela de 4 s que
				// chegou ao motor e a janela curta que confirmou o transiente.
				decision.Scores["cough_full"] = fullCough
			}
			m.recordAudio(ctx, decision)
		}
	}
}

// refineCough reclassifica apenas a faixa de pontuação em que a janela de
// 4 s pode ter diluído uma tosse curta. O custo extra é irrelevante porque
// poucas janelas por dia passam pelo piso, e o caminho silencioso continua
// fazendo uma única inferência.
func (m *Monitor) refineCough(ctx context.Context, cameraID string, samples []float32, full float64) (float64, bool) {
	if full < coughRefinementFloor || full >= rules.AudioThreshold("cough") {
		return full, false
	}
	size := audioSampleRate * coughRefinementWindowSeconds
	if len(samples) < size {
		return full, false
	}
	best := 0.0
	for offset := 0; offset+size <= len(samples); offset += audioSampleRate / 2 {
		results, err := m.Classifier.Classify(ctx, samples[offset:offset+size])
		if err != nil {
			if ctx.Err() == nil {
				m.Logger.Warn("refine cough audio", "camera", cameraID, "error", err)
			}
			return full, false
		}
		for _, result := range results {
			if result.Type == "cough" && result.Confidence > best {
				best = result.Confidence
			}
		}
	}
	if best >= coughRefinementThreshold {
		return best, true
	}
	return full, false
}

type cryEvidence struct {
	session   string
	end       time.Time
	lastEnd   time.Time
	pending   bool
	sustained bool
}

// Even strong model scores can be isolated false positives. Baby evidence must
// occur in two disjoint windows. Generic crying alone never becomes baby_cry.
func (g *cryEvidence) evaluate(w audioWindow, score float64) (bool, string) {
	if g.session != w.session || (!g.lastEnd.IsZero() && w.end.Sub(g.lastEnd) > 4*time.Second) {
		*g = cryEvidence{session: w.session}
	}
	if !g.lastEnd.IsZero() && !w.end.After(g.lastEnd) {
		return false, "repeated_window"
	}
	g.lastEnd = w.end
	if score < .50 {
		g.pending = false
		g.sustained = false
		return false, "below_baby_threshold"
	}
	if g.sustained {
		return true, "persistent_baby_cry"
	}
	if !g.pending {
		g.pending = true
		g.end = w.end
		return false, "awaiting_independent_baby_evidence"
	}
	if w.start.Before(g.end) {
		return false, "overlapping_baby_evidence"
	}
	g.pending = false
	g.sustained = true
	return true, "persistent_baby_cry"
}
