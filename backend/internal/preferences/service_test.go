package preferences

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/ferforastieri/valkyris/backend/internal/store"
)

func TestRetentionDefaultsAndPersistence(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	service := New(db, Retention{MaxAgeDays: 7, MaxStorageGB: 5})

	value, err := service.Retention(context.Background())
	if err != nil || value.MaxAgeDays != 7 || value.MaxStorageGB != 5 {
		t.Fatalf("unexpected defaults: value=%+v err=%v", value, err)
	}
	value, err = service.SetRetention(context.Background(), Retention{MaxAgeDays: 14, MaxStorageGB: 10})
	if err != nil {
		t.Fatal(err)
	}
	loaded, err := service.Retention(context.Background())
	if err != nil || loaded != value {
		t.Fatalf("retention was not persisted: value=%+v err=%v", loaded, err)
	}
}

func TestRetentionRejectsInvalidLimits(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	service := New(db, Retention{MaxAgeDays: 7, MaxStorageGB: 5})
	if _, err = service.SetRetention(context.Background(), Retention{MaxAgeDays: 0, MaxStorageGB: 5}); err == nil {
		t.Fatal("expected invalid age to fail")
	}
	if _, err = service.SetRetention(context.Background(), Retention{MaxAgeDays: 7, MaxStorageGB: 0}); err == nil {
		t.Fatal("expected invalid storage to fail")
	}
}
