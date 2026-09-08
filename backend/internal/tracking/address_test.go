package tracking

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAddressesResolveOffReportPathAndReuseCache(t *testing.T) {
	s, db := locationFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()
	s.now = func() time.Time { return now }
	calls := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Query().Get("lat") != "1.000000" || r.Header.Get("User-Agent") == "" {
			t.Error("missing coordinate or app identity")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"features":[{"properties":{"osm_id":123,"street":"Rua Central","city":"Cidade"}}]}`))
	}))
	defer provider.Close()
	if _, err := s.ReportMyLocation(ctx, "d", UserLocation{Latitude: 1, Accuracy: 8, Address: "Client address"}); err != nil {
		t.Fatal(err)
	}
	if calls != 0 {
		t.Fatal("report waited for geocoder")
	}
	if err := s.resolveNextAddress(ctx, provider.Client(), provider.URL); err != nil {
		t.Fatal(err)
	}
	history, err := s.UserHistory(ctx, "u", 20)
	if err != nil || history[0].Address != "Rua Central, Cidade" {
		t.Fatal(history, err)
	}
	// A second retained record for this coordinate reuses the persisted cache.
	if _, err := db.DB.Exec(`DELETE FROM user_locations`); err != nil {
		t.Fatal(err)
	}
	now = now.Add(time.Minute)
	if _, err := s.ReportMyLocation(ctx, "d", UserLocation{Latitude: 1, Accuracy: 8}); err != nil {
		t.Fatal(err)
	}
	if err := s.resolveNextAddress(ctx, provider.Client(), provider.URL); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("cache made %d requests", calls)
	}
	history, err = s.UserHistory(ctx, "u", 20)
	if err != nil || history[0].Address != "Rua Central, Cidade" {
		t.Fatal(history, err)
	}
}

func TestAddressFailureBackoffAndNamedArea(t *testing.T) {
	s, _ := locationFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()
	s.now = func() time.Time { return now }
	calls := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++; w.WriteHeader(503) }))
	defer provider.Close()
	if _, err := s.ReportMyLocation(ctx, "d", UserLocation{Accuracy: 8}); err != nil {
		t.Fatal(err)
	}
	if err := s.resolveNextAddress(ctx, provider.Client(), provider.URL); err != nil {
		t.Fatal(err)
	}
	if calls != 0 {
		t.Fatal("named area called external provider")
	}
	now = now.Add(time.Minute)
	if _, err := s.ReportMyLocation(ctx, "d", UserLocation{Latitude: 1, Accuracy: 8}); err != nil {
		t.Fatal(err)
	}
	if err := s.resolveNextAddress(ctx, provider.Client(), provider.URL); err == nil {
		t.Fatal("provider failure hidden")
	}
	if err := s.resolveNextAddress(ctx, provider.Client(), provider.URL); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatal("backoff ignored")
	}
	history, err := s.UserHistory(ctx, "u", 20)
	if err != nil || len(history) != 2 || history[1].Address != "Home" {
		t.Fatal(history, err)
	}
}
