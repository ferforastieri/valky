package detector

import (
	"context"
	"github.com/ferforastieri/valkyris/backend/internal/store"
	"testing"
	"time"
)

func TestAudioAuditRetention(t *testing.T) {
	db, err := store.Open(t.TempDir() + "/audit.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now().UTC()
	_, err = db.DB.Exec(`INSERT INTO cameras(id,name,host,username_enc,password_enc,rtsp_uri_enc,created_at,updated_at) VALUES('cam','Nursery','10.0.0.3',x'01',x'01',x'01',?,?)`, now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	if err != nil {
		t.Fatal(err)
	}
	a := AudioAudit{Store: db}
	d := AudioDecision{CameraID: "cam", Start: now.Add(-4 * time.Second), End: now, Session: "s", Scores: map[string]float64{"baby_cry": .2}, RMS: .01, Reason: "below_baby_threshold"}
	if err := a.Record(context.Background(), d); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.Exec(`UPDATE audio_decisions SET created_at=?`, now.Add(-25*time.Hour).Unix()); err != nil {
		t.Fatal(err)
	}
	a.prunedAt = time.Time{}
	if err := a.Record(context.Background(), d); err != nil {
		t.Fatal(err)
	}
	var count int
	var score float64
	if err := db.DB.QueryRow(`SELECT count(*),json_extract(scores_json,'$.baby_cry') FROM audio_decisions`).Scan(&count, &score); err != nil || count != 1 || score != .2 {
		t.Fatalf("count=%d score=%f err=%v", count, score, err)
	}
	if _, err := db.DB.Exec(`DELETE FROM cameras WHERE id='cam'`); err != nil {
		t.Fatal(err)
	}
	if err := db.DB.QueryRow(`SELECT count(*) FROM audio_decisions`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("camera deletion left audit rows: %d %v", count, err)
	}
}
