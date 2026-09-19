package updates

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

type releaseTransport func(*http.Request) (*http.Response, error)

func (f releaseTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestCheckReleaseAvailabilityAndCache(t *testing.T) {
	for _, tc := range []struct {
		name, server, client string
		wantServer, wantAPK  bool
	}{
		{"both", "v0.5.0", "0.4.0", true, true},
		{"apk only", "v0.6.0", "0.5.0", false, true},
		{"server only", "v0.5.0", "0.6.0", true, false},
		{"web", "v0.5.0", "", true, false},
		{"current", "v0.6.0", "0.6.0", false, false},
		{"newer installed", "v0.7.0", "0.7.0", false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			service := New(tc.server, "https://api.github.com/repos/ferforastieri/valkyris/releases/latest")
			service.http.Transport = releaseTransport(func(r *http.Request) (*http.Response, error) {
				calls++
				if r.Method != http.MethodGet || r.URL.Host != "api.github.com" {
					t.Fatalf("unexpected request: %s %s", r.Method, r.URL)
				}
				return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{"tag_name":"v0.6.0","html_url":"https://github.com/ferforastieri/valkyris/releases/tag/v0.6.0","assets":[{"name":"app.apk","browser_download_url":"https://github.com/ferforastieri/valkyris/releases/download/v0.6.0/app.apk"}]}`))}, nil
			})
			for range 2 {
				info, err := service.Check(t.Context(), tc.client)
				if err != nil || info.ServerUpdateAvailable != tc.wantServer || info.APKUpdateAvailable != tc.wantAPK || info.Available != (tc.wantServer || tc.wantAPK) || info.APKURL == "" {
					t.Fatalf("unexpected release info: %+v err=%v", info, err)
				}
			}
			if calls != 1 {
				t.Fatalf("release cache missed: %d requests", calls)
			}
		})
	}
}

func TestUntrustedAPKDoesNotEnableDownload(t *testing.T) {
	service := New("v0.6.0", "")
	var latest release
	latest.TagName = "v0.6.0"
	latest.Assets = append(latest.Assets, struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
	}{"app.apk", "https://untrusted.example/app.apk"})
	info := service.info(latest, "0.5.0")
	if info.APKURL != "" || info.APKUpdateAvailable || info.Available {
		t.Fatalf("untrusted APK was offered: %+v", info)
	}
}
