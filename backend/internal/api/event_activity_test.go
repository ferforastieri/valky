package api

import (
	"net/http/httptest"
	"testing"
)

func TestActivityRejectsInvalidFilters(t *testing.T) {
	s := &Server{}
	for _, query := range []string{"", "hours=0", "hours=13", "hours=49", "hours=no"} {
		w := httptest.NewRecorder()
		s.eventActivity(w, httptest.NewRequest("GET", "/events/activity?"+query, nil))
		if w.Code != 400 {
			t.Fatalf("%s: %d", query, w.Code)
		}
	}
	for _, query := range []string{"", "from=bad&to=bad", "from=2026-09-07T00:00:00Z&to=2026-09-06T00:00:00Z", "from=2026-09-01T00:00:00Z&to=2026-09-07T00:00:00Z", "from=2026-09-07T00:00:00Z&to=2026-09-07T01:00:00Z&offset=-1", "from=2026-09-07T00:00:00Z&to=2026-09-07T01:00:00Z&offset=x"} {
		w := httptest.NewRecorder()
		s.eventInterval(w, httptest.NewRequest("GET", "/events/interval?"+query, nil))
		if w.Code != 400 {
			t.Fatalf("%s: %d", query, w.Code)
		}
	}
}
