package updates

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCheckAndStartUpdate(t *testing.T) {
	var started string
	updater := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			t.Fatal("missing updater authorization")
		}
		var payload map[string]string
		_ = json.NewDecoder(r.Body).Decode(&payload)
		started = payload["version"]
		w.WriteHeader(http.StatusAccepted)
	}))
	defer updater.Close()
	releases := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"tag_name":"v0.6.0","html_url":"https://github.com/ferforastieri/valkyris/releases/tag/v0.6.0","published_at":"2026-09-03T00:00:00Z","assets":[{"name":"valkyris-v0.6.0.apk","browser_download_url":"https://github.com/ferforastieri/valkyris/releases/download/v0.6.0/valkyris-v0.6.0.apk"}]}`))
	}))
	defer releases.Close()

	service := New("v0.5.0", releases.URL, updater.URL, "secret")
	info, err := service.Check(t.Context(), "0.4.0")
	if err != nil || !info.Available || !info.ServerUpdateAvailable || !info.APKUpdateAvailable || info.APKURL == "" {
		t.Fatalf("unexpected update info: %+v err=%v", info, err)
	}
	info, err = service.Start(t.Context(), "0.4.0")
	if err != nil || started != "v0.6.0" || info.Message == "" {
		t.Fatalf("update not started: version=%q info=%+v err=%v", started, info, err)
	}
}

func TestOnlyAPKNeedsUpdate(t *testing.T) {
	releases := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"tag_name":"v0.6.0","html_url":"https://github.com/ferforastieri/valkyris/releases/tag/v0.6.0","assets":[{"name":"app.apk","browser_download_url":"https://github.com/ferforastieri/valkyris/releases/download/v0.6.0/app.apk"}]}`))
	}))
	defer releases.Close()
	service := New("v0.6.0", releases.URL, "", "")
	info, err := service.Start(t.Context(), "0.5.0")
	if err != nil || info.ServerUpdateAvailable || !info.APKUpdateAvailable {
		t.Fatalf("unexpected client-only update: %+v err=%v", info, err)
	}
}
