package api

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestViewerStaticFiles(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "index.html"), []byte("viewer"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "private"), 0700); err != nil {
		t.Fatal(err)
	}
	s := &Server{}
	s.SetViewerDirectory(dir)
	h := s.viewerHandler()
	for _, tc := range []struct {
		path   string
		status int
	}{{"/app/", 200}, {"/app/missing.js", 404}, {"/app/private/", 404}, {"/app/../secret", 404}, {"/app/.env", 404}} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", tc.path, nil))
		if w.Code != tc.status {
			t.Errorf("%s = %d", tc.path, w.Code)
		}
		if tc.status == 200 && (!strings.Contains(w.Header().Get("Content-Security-Policy"), "frame-ancestors 'none'") || w.Body.String() != "viewer") {
			t.Fatal("missing viewer or CSP")
		}
	}
}
