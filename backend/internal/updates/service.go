package updates

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Info struct {
	CurrentVersion        string    `json:"currentVersion"`
	ClientVersion         string    `json:"clientVersion,omitempty"`
	LatestVersion         string    `json:"latestVersion"`
	Available             bool      `json:"available"`
	ServerUpdateAvailable bool      `json:"serverUpdateAvailable"`
	APKUpdateAvailable    bool      `json:"apkUpdateAvailable"`
	ReleaseURL            string    `json:"releaseUrl"`
	APKURL                string    `json:"apkUrl,omitempty"`
	PublishedAt           time.Time `json:"publishedAt"`
	Message               string    `json:"message"`
}

type release struct {
	TagName     string `json:"tag_name"`
	HTMLURL     string `json:"html_url"`
	Draft       bool   `json:"draft"`
	Prerelease  bool   `json:"prerelease"`
	PublishedAt string `json:"published_at"`
	Assets      []struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
	} `json:"assets"`
}

type Service struct {
	current    string
	releaseAPI string
	updaterURL string
	token      string
	http       *http.Client
	mu         sync.Mutex
	cached     release
	cachedAt   time.Time
}

func New(current, releaseAPI, updaterURL, token string) *Service {
	return &Service{
		current: strings.TrimSpace(current), releaseAPI: strings.TrimSpace(releaseAPI),
		updaterURL: strings.TrimRight(strings.TrimSpace(updaterURL), "/"), token: strings.TrimSpace(token),
		http: &http.Client{Timeout: 12 * time.Second},
	}
}

func (s *Service) Check(ctx context.Context, clientVersion string) (Info, error) {
	latest, err := s.latest(ctx, false)
	if err != nil {
		return Info{}, err
	}
	return s.info(latest, clientVersion), nil
}

func (s *Service) Start(ctx context.Context, clientVersion string) (Info, error) {
	latest, err := s.latest(ctx, true)
	if err != nil {
		return Info{}, err
	}
	info := s.info(latest, clientVersion)
	if !info.Available {
		info.Message = "Valkyris and the Android app are already up to date"
		return info, nil
	}
	if info.ServerUpdateAvailable {
		if s.token == "" || s.updaterURL == "" {
			return Info{}, fmt.Errorf("automatic updater is not configured in this installation")
		}
		body, _ := json.Marshal(map[string]string{"version": info.LatestVersion})
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.updaterURL+"/v1/update", bytes.NewReader(body))
		if err != nil {
			return Info{}, err
		}
		req.Header.Set("Authorization", "Bearer "+s.token)
		req.Header.Set("Content-Type", "application/json")
		resp, err := s.http.Do(req)
		if err != nil {
			return Info{}, fmt.Errorf("updater unavailable: %w", err)
		}
		defer resp.Body.Close()
		responseBody, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		if resp.StatusCode != http.StatusAccepted {
			var envelope struct {
				Message string `json:"message"`
			}
			_ = json.Unmarshal(responseBody, &envelope)
			if envelope.Message == "" {
				envelope.Message = resp.Status
			}
			return Info{}, fmt.Errorf("updater rejected the request: %s", envelope.Message)
		}
		info.Message = "Backend update started; the latest Android APK is ready to download"
	} else {
		info.Message = "The backend is current; the latest Android APK is ready to download"
	}
	return info, nil
}

func (s *Service) latest(ctx context.Context, refresh bool) (release, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !refresh && s.cached.TagName != "" && time.Since(s.cachedAt) < 15*time.Minute {
		return s.cached, nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.releaseAPI, nil)
	if err != nil {
		return release{}, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "valkyris-updater")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	resp, err := s.http.Do(req)
	if err != nil {
		return release{}, fmt.Errorf("check latest GitHub release: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return release{}, fmt.Errorf("GitHub release check returned %s", resp.Status)
	}
	var latest release
	if err = json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&latest); err != nil {
		return release{}, fmt.Errorf("decode GitHub release: %w", err)
	}
	if latest.TagName == "" || latest.Draft || latest.Prerelease {
		return release{}, fmt.Errorf("GitHub did not return a stable Valkyris release")
	}
	s.cached, s.cachedAt = latest, time.Now()
	return latest, nil
}

func (s *Service) info(latest release, clientVersion string) Info {
	published, _ := time.Parse(time.RFC3339, latest.PublishedAt)
	serverAvailable := newer(latest.TagName, s.current)
	clientVersionBehind := strings.TrimSpace(clientVersion) != "" && newer(latest.TagName, clientVersion)
	info := Info{
		CurrentVersion: s.current, ClientVersion: clientVersion, LatestVersion: latest.TagName,
		ServerUpdateAvailable: serverAvailable, ReleaseURL: trustedURL(latest.HTMLURL), PublishedAt: published,
	}
	for _, asset := range latest.Assets {
		if strings.HasSuffix(strings.ToLower(asset.Name), ".apk") {
			if candidate := trustedURL(asset.BrowserDownloadURL); candidate != "" {
				info.APKURL = candidate
				break
			}
		}
	}
	info.APKUpdateAvailable = clientVersionBehind && info.APKURL != ""
	info.Available = info.ServerUpdateAvailable || info.APKUpdateAvailable
	if info.Available {
		info.Message = "A new Valkyris release is available: " + latest.TagName
	} else {
		info.Message = "Valkyris is up to date"
	}
	return info
}

func newer(latest, current string) bool {
	if current == "" || current == "dev" || current == "development" {
		return false
	}
	a, okA := versionParts(latest)
	b, okB := versionParts(current)
	if !okA || !okB {
		return strings.TrimPrefix(latest, "v") != strings.TrimPrefix(current, "v")
	}
	for i := range a {
		if a[i] != b[i] {
			return a[i] > b[i]
		}
	}
	return false
}

func versionParts(value string) ([3]int, bool) {
	var result [3]int
	core := strings.SplitN(strings.TrimPrefix(strings.TrimSpace(value), "v"), "-", 2)[0]
	parts := strings.Split(core, ".")
	if len(parts) != 3 {
		return result, false
	}
	for i, part := range parts {
		parsed, err := strconv.Atoi(part)
		if err != nil || parsed < 0 {
			return result, false
		}
		result[i] = parsed
	}
	return result, true
}

func trustedURL(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil {
		return ""
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "github.com" && !strings.HasSuffix(host, ".githubusercontent.com") {
		return ""
	}
	return parsed.String()
}
