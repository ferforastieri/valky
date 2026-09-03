package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"log/slog"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/api"
	"github.com/ferforastieri/valkyris/backend/internal/app"
	"github.com/ferforastieri/valkyris/backend/internal/auth"
	"github.com/ferforastieri/valkyris/backend/internal/camera"
	"github.com/ferforastieri/valkyris/backend/internal/config"
	appcrypto "github.com/ferforastieri/valkyris/backend/internal/crypto"
	"github.com/ferforastieri/valkyris/backend/internal/detector"
	"github.com/ferforastieri/valkyris/backend/internal/event"
	"github.com/ferforastieri/valkyris/backend/internal/media"
	"github.com/ferforastieri/valkyris/backend/internal/notify"
	"github.com/ferforastieri/valkyris/backend/internal/rules"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/ferforastieri/valkyris/backend/internal/updates"
)

var version = "dev"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	cfg := config.Load()
	if err := cfg.Prepare(); err != nil {
		logger.Error("prepare data directory", "error", err)
		os.Exit(1)
	}
	if err := ensureCertificate(cfg.TLSCert, cfg.TLSKey); err != nil {
		logger.Error("prepare TLS", "error", err)
		os.Exit(1)
	}
	db, err := store.Open(cfg.DatabasePath)
	if err != nil {
		logger.Error("open store", "error", err)
		os.Exit(1)
	}
	defer db.Close()
	vault, err := appcrypto.LoadOrCreate(cfg.MasterKeyFile)
	if err != nil {
		logger.Error("open vault", "error", err)
		os.Exit(1)
	}
	cameraRepo := camera.NewRepository(db, vault)
	onvif := camera.NewONVIFClient()
	mediaManager := media.New(cfg.MediaAPI, cfg.MediaURL, cfg.RecordingsDir)
	authManager := auth.NewManager(db, cfg.PairingLifetime)
	rulesService := rules.NewService(db)
	eventService := event.NewService(db)
	notifyService := notify.NewService(db, vault)
	hub := api.NewHub()
	application := &app.Service{Rules: rulesService, Events: eventService, Cameras: cameraRepo, Media: mediaManager, Notify: notifyService, Hub: hub, DataDir: cfg.DataDir, Logger: logger}
	apiServer := api.NewServer(authManager, cameraRepo, onvif, mediaManager, rulesService, eventService, notifyService, hub, logger)
	apiServer.SetSubmitter(application)
	apiServer.SetUpdates(updates.New(version, cfg.ReleaseAPI, cfg.UpdaterURL, cfg.UpdaterToken))
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	go notifyService.Run(ctx)
	go application.RunRetention(ctx, cfg.RetentionAge, cfg.RetentionBytes)
	restoreMedia(ctx, cameraRepo, mediaManager, logger)
	var classifier *detector.NativeClassifier
	model := filepath.Join(cfg.ModelsDir, "model.int8.onnx")
	labels := filepath.Join(cfg.ModelsDir, "class_labels_indices.csv")
	if _, modelErr := os.Stat(model); modelErr == nil {
		classifier, modelErr = detector.NewNativeClassifier(model, labels)
		if modelErr != nil {
			logger.Warn("audio classifier disabled", "error", modelErr)
		} else {
			defer classifier.Close()
		}
	} else {
		logger.Warn("audio classifier disabled", "reason", "model files not installed")
	}
	monitor := &detector.Monitor{Cameras: cameraRepo, Media: mediaManager, ONVIF: onvif, Classifier: classifier, DataDir: cfg.DataDir, Logger: logger, Submit: func(ctx context.Context, detection rules.Detection) error {
		_, err := application.Submit(ctx, detection)
		return err
	}}
	go monitor.Run(ctx)
	httpServer := &http.Server{Addr: cfg.Listen, Handler: apiServer.Handler(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 2 * time.Minute}
	go func() {
		logger.Info("Valkyris listening", "address", cfg.Listen, "version", version)
		if e := httpServer.ListenAndServeTLS(cfg.TLSCert, cfg.TLSKey); e != nil && e != http.ErrServerClosed {
			logger.Error("server stopped", "error", e)
			cancel()
		}
	}()
	<-ctx.Done()
	shutdown, c := context.WithTimeout(context.Background(), 10*time.Second)
	defer c()
	_ = httpServer.Shutdown(shutdown)
}
func restoreMedia(ctx context.Context, repo *camera.Repository, m *media.Manager, logger *slog.Logger) {
	cams, err := repo.List(ctx)
	if err != nil {
		return
	}
	for _, cam := range cams {
		_, cred, e := repo.Get(ctx, cam.ID)
		if e == nil {
			if e = m.ConfigureCamera(ctx, cam.ID, cred.RTSPURI); e != nil {
				logger.Warn("restore media path", "camera", cam.ID, "error", e)
			}
		}
	}
}
func ensureCertificate(certPath, keyPath string) error {
	if _, err := os.Stat(certPath); err == nil {
		if _, err = os.Stat(keyPath); err == nil {
			return nil
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	template := x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "valkyris.local", Organization: []string{"Valkyris local installation"}}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(10, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, DNSNames: []string{"valkyris.local", "localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")}, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return err
	}
	certOut, err := os.OpenFile(certPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if err = pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: der}); err != nil {
		certOut.Close()
		return err
	}
	certOut.Close()
	keyBytes, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	keyOut, err := os.OpenFile(keyPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	defer keyOut.Close()
	return pem.Encode(keyOut, &pem.Block{Type: "PRIVATE KEY", Bytes: keyBytes})
}
