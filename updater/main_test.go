package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUpdateRejectsUnauthorizedAndInvalidVersions(t *testing.T) {
	u := &updater{token: "secret"}
	request := httptest.NewRequest(http.MethodPost, "/v1/update", strings.NewReader(`{"version":"v0.6.0"}`))
	response := httptest.NewRecorder()
	u.update(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d", response.Code)
	}

	request = httptest.NewRequest(http.MethodPost, "/v1/update", strings.NewReader(`{"version":"latest; rm -rf /"}`))
	request.Header.Set("Authorization", "Bearer secret")
	response = httptest.NewRecorder()
	u.update(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid version status = %d", response.Code)
	}
}

func TestSetEnvValuePreservesOtherSettings(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".env")
	if err := os.WriteFile(path, []byte("VALKYRIS_PORT=9443\nVALKYRIS_VERSION=v0.5.0\nSECRET=value\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := setEnvValue(path, "VALKYRIS_VERSION", "v0.6.0"); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(content)
	if !strings.Contains(text, "VALKYRIS_VERSION=v0.6.0") || !strings.Contains(text, "VALKYRIS_PORT=9443") || !strings.Contains(text, "SECRET=value") {
		t.Fatalf("unexpected env content: %s", text)
	}
	if got := readEnvValue(path, "VALKYRIS_VERSION"); got != "v0.6.0" {
		t.Fatalf("version = %q", got)
	}
}
