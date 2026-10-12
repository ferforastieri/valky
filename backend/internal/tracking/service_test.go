package tracking

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestReportMyLocationIgnoresClientAddress(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "valkyris.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := db.DB.ExecContext(context.Background(), `INSERT INTO users(id,name,created_at,updated_at) VALUES(?,?,?,?)`, "user-1", "Fernando", now, now); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(context.Background(), `INSERT INTO devices(id,user_id,name,token_hash,created_at) VALUES(?,?,?,?,?)`, "device-1", "user-1", "Telefone", []byte("token-1"), now); err != nil {
		t.Fatal(err)
	}
	service := New(db)
	if _, err := service.ReportMyLocation(context.Background(), "device-1", UserLocation{
		Latitude: -23.5505, Longitude: -46.6333, Accuracy: 8, Address: "Avenida Paulista, São Paulo - SP",
	}); err != nil {
		t.Fatal(err)
	}
	history, err := service.UserHistoryPage(context.Background(), "user-1", 10, 0)
	if err != nil || len(history) != 1 {
		t.Fatalf("history=%d err=%v", len(history), err)
	}
	if got, want := history[0].Address, ""; got != want {
		t.Fatalf("address=%q want %q", got, want)
	}
}
