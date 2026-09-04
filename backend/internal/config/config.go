package config

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
)

type Config struct {
	Listen          string
	DataDir         string
	DatabasePath    string
	TLSCert         string
	TLSKey          string
	MasterKeyFile   string
	MediaURL        string
	MediaAPI        string
	MediaPlayback   string
	RecordingsDir   string
	ModelsDir       string
	UpdaterURL      string
	UpdaterToken    string
	ReleaseAPI      string
	RetentionAge    time.Duration
	RetentionBytes  int64
	PairingLifetime time.Duration
}

func Load() Config {
	data := env("VALKYRIS_DATA_DIR", "./data")
	return Config{
		Listen:          env("VALKYRIS_LISTEN", ":8443"),
		DataDir:         data,
		DatabasePath:    env("VALKYRIS_DATABASE", filepath.Join(data, "valkyris.db")),
		TLSCert:         env("VALKYRIS_TLS_CERT", filepath.Join(data, "tls", "server.crt")),
		TLSKey:          env("VALKYRIS_TLS_KEY", filepath.Join(data, "tls", "server.key")),
		MasterKeyFile:   env("VALKYRIS_MASTER_KEY_FILE", filepath.Join(data, "secrets", "master.key")),
		MediaURL:        env("VALKYRIS_MEDIA_URL", "http://localhost:8888"),
		MediaAPI:        env("VALKYRIS_MEDIA_API", "http://localhost:9997"),
		MediaPlayback:   env("VALKYRIS_MEDIA_PLAYBACK", "http://localhost:9996"),
		RecordingsDir:   env("VALKYRIS_MEDIA_RECORDINGS", filepath.Join(data, "recordings")),
		ModelsDir:       env("VALKYRIS_MODELS_DIR", "./models"),
		UpdaterURL:      env("VALKYRIS_UPDATER_URL", "http://updater:8080"),
		UpdaterToken:    os.Getenv("VALKYRIS_UPDATER_TOKEN"),
		ReleaseAPI:      env("VALKYRIS_RELEASE_API", "https://api.github.com/repos/ferforastieri/valkyris/releases/latest"),
		RetentionAge:    7 * 24 * time.Hour,
		RetentionBytes:  5 * 1024 * 1024 * 1024,
		PairingLifetime: 10 * time.Minute,
	}
}

func (c Config) Prepare() error {
	for _, dir := range []string{c.DataDir, filepath.Dir(c.DatabasePath), filepath.Dir(c.TLSCert), filepath.Dir(c.MasterKeyFile), c.RecordingsDir} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("create %s: %w", dir, err)
		}
	}
	return nil
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
