package api

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func (s *Server) SetViewerDirectory(dir string) { s.viewerDir = dir }
func (s *Server) viewerHandler() http.Handler {
	dir := s.viewerDir
	if dir == "" {
		dir = "/opt/valky/web"
	}
	files := http.StripPrefix("/app/", http.FileServer(http.Dir(dir)))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name := strings.TrimPrefix(r.URL.Path, "/app/")
		if name == "" {
			name = "index.html"
		}
		if strings.Contains(name, "..") || strings.HasPrefix(name, ".") {
			http.NotFound(w, r)
			return
		}
		stat, err := os.Stat(filepath.Join(dir, filepath.FromSlash(name)))
		if err != nil || stat.IsDir() {
			http.NotFound(w, r)
			return
		}
		// Os nomes em _astro/ carregam o hash do conteúdo: podem ser cacheados
		// para sempre. O resto (index.html) continua sendo revalidado.
		if strings.HasPrefix(name, "_astro/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		w.Header().Set("X-Robots-Tag", "noindex, nofollow")
		w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data: https://tile.openstreetmap.org; media-src 'self' blob:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")
		files.ServeHTTP(w, r)
	})
}
