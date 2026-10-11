package notify

import (
	"regexp"
	"testing"
)

// FCM HTTP v1 rejects a TTL such as "10m" with 400 INVALID_ARGUMENT. The
// duration must be expressed in seconds, so a regression here silently breaks
// every alert. This guards the wire format.
func TestFCMTTLUsesSeconds(t *testing.T) {
	if !regexp.MustCompile(`^\d+s$`).MatchString(fcmTTL) {
		t.Fatalf("fcmTTL = %q; FCM requires a seconds duration like \"600s\"", fcmTTL)
	}
}
