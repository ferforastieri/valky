package config

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
)

type Config struct {
	GeocoderURL         string
	ViewerDir           string
	Listen              string
	DataDir             string
	DatabasePath        string
	TLSCert             string
	TLSKey              string
	MasterKeyFile       string
	MediaAPI            string
	MediaPlayback       string
	MediaWebRTC         string
	MediaRTSP           string
	RecordingsDir       string
	ModelsDir           string
	ReleaseAPI          string
	FirebaseCredentials string
	RetentionAge        time.Duration
	RetentionBytes      int64
	PairingLifetime     time.Duration
}

func Load() Config {
	data := env("VALKY_DATA_DIR", "./data")
	return Config{
		GeocoderURL:         env("VALKY_GEOCODER_URL", "https://photon.komoot.io/reverse"),
		ViewerDir:           env("VALKY_WEB_DIR", "/opt/valky/web"),
		Listen:              env("VALKY_LISTEN", ":8443"),
		DataDir:             data,
		DatabasePath:        env("VALKY_DATABASE", filepath.Join(data, "valky.db")),
		TLSCert:             env("VALKY_TLS_CERT", filepath.Join(data, "tls", "server.crt")),
		TLSKey:              env("VALKY_TLS_KEY", filepath.Join(data, "tls", "server.key")),
		MasterKeyFile:       env("VALKY_MASTER_KEY_FILE", filepath.Join(data, "secrets", "master.key")),
		MediaAPI:            env("VALKY_MEDIA_API", "http://localhost:9997"),
		MediaPlayback:       env("VALKY_MEDIA_PLAYBACK", "http://localhost:9996"),
		MediaWebRTC:         env("VALKY_MEDIA_WEBRTC", "http://localhost:8889"),
		MediaRTSP:           env("VALKY_MEDIA_RTSP", "rtsp://localhost:8554"),
		RecordingsDir:       env("VALKY_MEDIA_RECORDINGS", filepath.Join(data, "recordings")),
		ModelsDir:           env("VALKY_MODELS_DIR", "./models"),
		ReleaseAPI:          env("VALKY_RELEASE_API", "https://api.github.com/repos/ferforastieri/valky/releases/latest"),
		FirebaseCredentials: os.Getenv("VALKY_FIREBASE_CREDENTIALS_FILE"),
		RetentionAge:        7 * 24 * time.Hour,
		RetentionBytes:      5 * 1024 * 1024 * 1024,
		PairingLifetime:     10 * time.Minute,
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
