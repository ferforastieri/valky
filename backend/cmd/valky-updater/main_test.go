package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type fakeRunner struct {
	output string
	err    error
	start  chan struct{}
	done   chan struct{}
}

func (f *fakeRunner) Run(context.Context) (string, error) {
	if f.start != nil {
		close(f.start)
	}
	if f.done != nil {
		<-f.done
	}
	return f.output, f.err
}

func TestUpdaterAuthAndFlow(t *testing.T) {
	runner := &fakeRunner{output: "instalador ok", start: make(chan struct{}), done: make(chan struct{})}
	server := newServer("secret", runner)
	httpServer := httptest.NewServer(server.routes())
	defer httpServer.Close()
	client := httpServer.Client()

	request := func(method, path, token string) *http.Response {
		t.Helper()
		req, _ := http.NewRequest(method, httpServer.URL+path, nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return res
	}

	if res := request(http.MethodGet, "/status", ""); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("sem token = %d", res.StatusCode)
	}
	if res := request(http.MethodGet, "/status", "secret"); res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", res.StatusCode)
	}
	if res := request(http.MethodPost, "/update", "secret"); res.StatusCode != http.StatusAccepted {
		t.Fatalf("update = %d", res.StatusCode)
	}
	<-runner.start
	if res := request(http.MethodPost, "/update", "secret"); res.StatusCode != http.StatusConflict {
		t.Fatalf("update concorrente = %d", res.StatusCode)
	}
	close(runner.done)
	waitState(t, client, httpServer.URL, "succeeded")

	res := request(http.MethodGet, "/status", "secret")
	defer res.Body.Close()
	var item status
	if err := json.NewDecoder(res.Body).Decode(&item); err != nil {
		t.Fatal(err)
	}
	if item.Output != "instalador ok" || item.FinishedAt.IsZero() {
		t.Fatalf("status final = %+v", item)
	}
}

func TestUpdaterFailureState(t *testing.T) {
	server := newServer("secret", &fakeRunner{err: errors.New("falhou")})
	httpServer := httptest.NewServer(server.routes())
	defer httpServer.Close()
	client := httpServer.Client()
	req, _ := http.NewRequest(http.MethodPost, httpServer.URL+"/update", nil)
	req.Header.Set("Authorization", "Bearer secret")
	res, err := client.Do(req)
	if err != nil || res.StatusCode != http.StatusAccepted {
		t.Fatalf("update = %v %v", res, err)
	}
	waitState(t, client, httpServer.URL, "failed")
}

func waitState(t *testing.T, client *http.Client, base, want string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		req, _ := http.NewRequest(http.MethodGet, base+"/status", nil)
		req.Header.Set("Authorization", "Bearer secret")
		res, err := client.Do(req)
		if err == nil {
			var item status
			_ = json.NewDecoder(res.Body).Decode(&item)
			res.Body.Close()
			if item.State == want {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("estado não chegou a %s", want)
}

func TestVerifyChecksum(t *testing.T) {
	content := []byte("#!/bin/sh\necho ok\n")
	sum := sha256.Sum256(content)
	checksums := []byte(hex.EncodeToString(sum[:]) + "  install.sh\n")
	if err := verifyChecksum(content, checksums, "install.sh"); err != nil {
		t.Fatal(err)
	}
	if err := verifyChecksum(append(content, 'x'), checksums, "install.sh"); err == nil {
		t.Fatal("checksum adulterado foi aceito")
	}
	if err := verifyChecksum(content, checksums, "outro.sh"); err == nil {
		t.Fatal("asset ausente foi aceito")
	}
}
