package rules

import "time"

func AudioThreshold(kind string) float64 {
	switch kind {
	case "motion", "person", "tamper":
		return .20
	case "baby_cry", "crying", "scream":
		return .78
	case "glass_break", "smoke_alarm", "fire_alarm", "siren":
		return .85
	case "doorbell", "knock", "dog_bark":
		return .72
	default:
		return .70
	}
}

type audioConfirmation struct {
	end     time.Time
	session string
	count   int
	updated time.Time
}

// Overlapping windows cannot satisfy a rule's independent confirmations.
func (s *Service) confirmAudio(key string, r Rule, d Detection) bool {
	a := d.Audio
	previous := s.audioPending[key]
	if previous.session != a.Session || previous.updated != r.UpdatedAt || a.End.Sub(previous.end) > 4*time.Second {
		previous = audioConfirmation{session: a.Session, updated: r.UpdatedAt}
	}
	if !a.TemporalAccepted {
		delete(s.audioPending, key)
		return false
	}
	if a.Start.Before(previous.end) {
		return false
	}
	previous.end = a.End
	previous.count++
	if previous.count < r.Confirmations {
		s.audioPending[key] = previous
		return false
	}
	previous.count = 0
	s.audioPending[key] = previous
	return true
}
