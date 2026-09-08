package api

import (
	"crypto/sha256"
	"fmt"
	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type limitWindow struct {
	count int
	until time.Time
}
type requestLimits struct {
	mu      sync.Mutex
	windows map[string]limitWindow
}

func newRequestLimits() *requestLimits { return &requestLimits{windows: make(map[string]limitWindow)} }
func (l *requestLimits) allow(key string, max int, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	w := l.windows[key]
	if !now.Before(w.until) {
		w = limitWindow{until: now.Add(time.Minute)}
	}
	if w.count >= max {
		return false
	}
	if len(l.windows) >= 10000 {
		for k, v := range l.windows {
			if !now.Before(v.until) {
				delete(l.windows, k)
			}
		}
		if _, ok := l.windows[key]; !ok && len(l.windows) >= 10000 {
			return false
		}
	}
	w.count++
	l.windows[key] = w
	return true
}
func (l *requestLimits) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/v1/") {
			next.ServeHTTP(w, r)
			return
		}
		now := time.Now()
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			ip = r.RemoteAddr
		}
		// Never trust client-supplied forwarding headers. A reverse proxy shares the
		// origin budget; authenticated media also has a per-session budget.
		allowed := l.allow("all", 3000, now) && l.allow("ip:"+ip, 1200, now)
		path := r.URL.Path
		if r.Method == "POST" && (path == "/api/v1/login" || path == "/api/v1/pair" || path == "/api/v1/admin/bootstrap" || path == "/api/v1/me/credentials" || path == "/api/v1/me/password") {
			r.Body = http.MaxBytesReader(w, r.Body, 8192)
			allowed = allowed && l.allow("auth-global", 30, now) && l.allow("auth:"+ip, 10, now)
		}
		credential := r.Header.Get("Authorization")
		if credential == "" {
			if c, e := r.Cookie(auth.ViewerCookie); e == nil {
				credential = c.Value
			}
		}
		identity := fmt.Sprintf("%x", sha256.Sum256([]byte(ip+"/"+credential)))
		if strings.HasSuffix(path, "/snapshot") || strings.HasSuffix(path, "/recording") {
			allowed = allowed && l.allow("media:"+identity, 60, now)
		}
		if r.Method == "POST" && strings.HasSuffix(path, "/whep") {
			allowed = allowed && l.allow("whep:"+identity, 12, now)
		}
		if !allowed {
			w.Header().Set("Retry-After", strconv.Itoa(60))
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(429)
			w.Write([]byte(`{"success":false,"message":"Muitas tentativas. Aguarde um minuto e tente novamente."}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}
