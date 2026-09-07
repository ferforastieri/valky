package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLoginRateLimitCannotBeBypassedByForwardingHeaders(t *testing.T) {
	h := newRequestLimits().wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	for i := 0; i < 11; i++ {
		r := httptest.NewRequest("POST", "/api/v1/login", nil)
		r.Header.Set("X-Forwarded-For", time.Now().String())
		r.Header.Set("CF-Connecting-IP", time.Now().String())
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		want := 204
		if i == 10 {
			want = 429
		}
		if w.Code != want {
			t.Fatalf("request %d: %d", i, w.Code)
		}
		if i == 10 && w.Header().Get("Retry-After") == "" {
			t.Fatal("missing retry")
		}
	}
	r := httptest.NewRequest("GET", "/api/v1/cameras", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 204 {
		t.Fatal("login budget blocked authenticated API")
	}
}
func TestLimitWindowExpires(t *testing.T) {
	l := newRequestLimits()
	now := time.Now()
	if !l.allow("a", 1, now) || l.allow("a", 1, now) || !l.allow("a", 1, now.Add(time.Minute)) {
		t.Fatal("bad expiry")
	}
}
