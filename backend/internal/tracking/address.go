package tracking

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// RunAddresses enriches retained coordinates without delaying location reports.
// A single worker, persistent spatial cache and retry backoff bound provider load.
func (s *Service) RunAddresses(ctx context.Context, endpoint string, logger *slog.Logger) {
	if endpoint == "" {
		return
	}
	client := &http.Client{Timeout: 5 * time.Second}
	tick := time.NewTicker(10 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			if err := s.resolveNextAddress(ctx, client, endpoint); err != nil && ctx.Err() == nil {
				logger.Warn("resolve location address", "error", err)
			}
		}
	}
}

func (s *Service) resolveNextAddress(ctx context.Context, client *http.Client, endpoint string) error {
	var lat, lon float64
	var cell string
	now := s.now().UTC()
	err := s.store.DB.QueryRowContext(ctx, `SELECT l.latitude,l.longitude,printf('%.4f,%.4f',l.latitude,l.longitude) FROM user_locations l LEFT JOIN location_address_cache c ON c.cell=printf('%.4f,%.4f',l.latitude,l.longitude) WHERE l.address='' AND l.accuracy>0 AND l.accuracy<=? AND (c.cell IS NULL OR c.retry_after<=?) ORDER BY l.occurred_at DESC LIMIT 1`, historyAccuracy, now.Unix()).Scan(&lat, &lon, &cell)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	// A named home area is more useful than an approximate street from a provider.
	places, err := s.ListPlaces(ctx)
	if err != nil {
		return err
	}
	address := ""
	for _, place := range places {
		if place.Enabled && distanceMeters(lat, lon, place.Latitude, place.Longitude) < place.RadiusMeters {
			address = place.Name
			break
		}
	}
	if address == "" {
		address, err = reverseAddress(ctx, client, endpoint, lat, lon)
	}
	retry := now.Add(24 * time.Hour).Unix()
	if err != nil || address == "" {
		retry = now.Add(time.Hour).Unix()
	}
	tx, e := s.store.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(ctx, `INSERT INTO location_address_cache(cell,address,retry_after) VALUES(?,?,?) ON CONFLICT(cell) DO UPDATE SET address=excluded.address,retry_after=excluded.retry_after`, cell, address, retry); e != nil {
		return e
	}
	if address != "" {
		if _, e = tx.ExecContext(ctx, `UPDATE user_locations SET address=? WHERE address='' AND printf('%.4f,%.4f',latitude,longitude)=?`, address, cell); e != nil {
			return e
		}
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	// Provider errors are intentionally generic: never log coordinate-bearing URLs.
	return err
}

func reverseAddress(ctx context.Context, client *http.Client, endpoint string, lat, lon float64) (string, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return "", fmt.Errorf("invalid geocoder endpoint")
	}
	q := u.Query()
	q.Set("lat", fmt.Sprintf("%.6f", lat))
	q.Set("lon", fmt.Sprintf("%.6f", lon))
	q.Set("limit", "1")
	q.Set("radius", "0.2")
	u.RawQuery = q.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return "", fmt.Errorf("invalid geocoder request")
	}
	req.Header.Set("User-Agent", "Valkyris/2 (https://github.com/ferforastieri/valkyris)")
	response, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("geocoder unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("geocoder returned HTTP %d", response.StatusCode)
	}
	// Photon also returns numeric OSM properties; decode only the address fields.
	var result struct {
		Features []struct {
			Properties struct {
				Name     string `json:"name"`
				Street   string `json:"street"`
				City     string `json:"city"`
				District string `json:"district"`
				State    string `json:"state"`
			} `json:"properties"`
		} `json:"features"`
	}
	if err = json.NewDecoder(io.LimitReader(response.Body, 128<<10)).Decode(&result); err != nil {
		return "", fmt.Errorf("invalid geocoder response")
	}
	if len(result.Features) == 0 {
		return "", nil
	}
	p := result.Features[0].Properties
	street := p.Street
	if street == "" {
		street = p.Name
	}
	parts := []string{}
	for _, part := range []string{street, p.District, p.City, p.State} {
		if part != "" && (len(parts) == 0 || parts[len(parts)-1] != part) {
			parts = append(parts, part)
		}
	}
	address := []rune(strings.Join(parts, ", "))
	if len(address) > 320 {
		address = address[:320]
	}
	return string(address), nil
}
