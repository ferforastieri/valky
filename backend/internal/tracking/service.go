package tracking

import (
	"context"
	"database/sql"
	"encoding/base64"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/ferforastieri/valkyris/backend/internal/store"
	"github.com/google/uuid"
)

type Person struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Color         string     `json:"color"`
	DeviceID      string     `json:"deviceId,omitempty"`
	Enabled       bool       `json:"enabled"`
	LastLatitude  *float64   `json:"lastLatitude,omitempty"`
	LastLongitude *float64   `json:"lastLongitude,omitempty"`
	LastAccuracy  *float64   `json:"lastAccuracy,omitempty"`
	LastLocatedAt *time.Time `json:"lastLocatedAt,omitempty"`
	CreatedAt     time.Time  `json:"createdAt"`
	UpdatedAt     time.Time  `json:"updatedAt"`
}

type Place struct {
	ID           string    `json:"id"`
	Name         string    `json:"name"`
	Latitude     float64   `json:"latitude"`
	Longitude    float64   `json:"longitude"`
	RadiusMeters float64   `json:"radiusMeters"`
	Enabled      bool      `json:"enabled"`
	CreatedAt    time.Time `json:"createdAt"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

type Location struct {
	ID         string    `json:"id"`
	PersonID   string    `json:"personId"`
	Latitude   float64   `json:"latitude"`
	Longitude  float64   `json:"longitude"`
	Accuracy   float64   `json:"accuracy"`
	OccurredAt time.Time `json:"occurredAt"`
}

type Transition struct {
	Person  Person
	Place   Place
	Entered bool
	At      time.Time
}

type Service struct {
	store *store.Store
	now   func() time.Time
}

func New(s *store.Store) *Service { return &Service{store: s, now: time.Now} }

// User is a family profile. It deliberately has no device credential in its
// public representation: a device belongs to a user, not the other way round.
type User struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Color         string     `json:"color"`
	AvatarData    string     `json:"avatarData,omitempty"`
	Enabled       bool       `json:"enabled"`
	LastLatitude  *float64   `json:"lastLatitude,omitempty"`
	LastLongitude *float64   `json:"lastLongitude,omitempty"`
	LastAccuracy  *float64   `json:"lastAccuracy,omitempty"`
	LastLocatedAt *time.Time `json:"lastLocatedAt,omitempty"`
	CreatedAt     time.Time  `json:"createdAt"`
	UpdatedAt     time.Time  `json:"updatedAt"`
}

type UserLocation struct {
	ID         string    `json:"id"`
	UserID     string    `json:"userId"`
	Latitude   float64   `json:"latitude"`
	Longitude  float64   `json:"longitude"`
	Accuracy   float64   `json:"accuracy"`
	Address    string    `json:"address"`
	LastSeenAt time.Time `json:"lastSeenAt"`
	OccurredAt time.Time `json:"occurredAt"`
}

type UserTransition struct {
	User    User
	Place   Place
	Entered bool
	At      time.Time
}

func (s *Service) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id,name,color,avatar_data,enabled,last_latitude,last_longitude,last_accuracy,last_located_at,created_at,updated_at FROM users WHERE enabled=1 ORDER BY name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	users := make([]User, 0)
	for rows.Next() {
		user, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		users = append(users, user)
	}
	return users, rows.Err()
}

// CurrentUser returns the family profile owned by the authenticated phone.
// A profile is never created separately from a device.
func (s *Service) CurrentUser(ctx context.Context, deviceID string) (User, error) {
	return scanUser(s.store.DB.QueryRowContext(ctx, `SELECT u.id,u.name,u.color,u.avatar_data,u.enabled,u.last_latitude,u.last_longitude,u.last_accuracy,u.last_located_at,u.created_at,u.updated_at FROM users u JOIN devices d ON d.user_id=u.id WHERE d.id=? AND d.enabled=1`, deviceID))
}

func (s *Service) UpdateUser(ctx context.Context, id string, in User) (User, error) {
	name := strings.TrimSpace(in.Name)
	if name == "" {
		return User{}, fmt.Errorf("user name is required")
	}
	color := in.Color
	if color == "" {
		color = "#5B5BD6"
	}
	avatarData := strings.TrimSpace(in.AvatarData)
	if avatarData != "" && !validAvatarData(avatarData) {
		return User{}, fmt.Errorf("profile photo is invalid")
	}
	now := s.now().UTC().Format(time.RFC3339Nano)
	result, err := s.store.DB.ExecContext(ctx, `UPDATE users SET name=?,color=?,avatar_data=?,updated_at=? WHERE id=?`, name, color, avatarData, now, id)
	if err != nil {
		return User{}, err
	}
	if affected, _ := result.RowsAffected(); affected == 0 {
		return User{}, sql.ErrNoRows
	}
	return scanUser(s.store.DB.QueryRowContext(ctx, `SELECT id,name,color,avatar_data,enabled,last_latitude,last_longitude,last_accuracy,last_located_at,created_at,updated_at FROM users WHERE id=?`, id))
}

func (s *Service) UpdateCurrentUser(ctx context.Context, deviceID string, in User) (User, error) {
	current, err := s.CurrentUser(ctx, deviceID)
	if err != nil {
		return User{}, err
	}
	return s.UpdateUser(ctx, current.ID, in)
}

// ReportMyLocation resolves the authenticated device to its linked user. The
// caller can never choose another family member's profile.
func (s *Service) ReportMyLocation(ctx context.Context, deviceID string, location UserLocation) ([]UserTransition, error) {
	user, err := s.CurrentUser(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	if !user.Enabled {
		return nil, fmt.Errorf("user tracking is disabled")
	}
	if !validCoordinate(location.Latitude, location.Longitude) || math.IsNaN(location.Accuracy) || math.IsInf(location.Accuracy, 0) || location.Accuracy < 0 || location.Accuracy > 10000 {
		return nil, fmt.Errorf("location is invalid")
	}
	// Older clients may still send an address; only the server resolves it.
	location.Address = ""
	now := s.now().UTC()
	if location.OccurredAt.IsZero() {
		location.OccurredAt = now
	}
	if location.OccurredAt.Before(now.Add(-24*time.Hour)) || location.OccurredAt.After(now.Add(5*time.Minute)) {
		return nil, fmt.Errorf("location timestamp is invalid")
	}
	// A stale provider cache is not a new location observation.
	if location.OccurredAt.Before(now.Add(-2*time.Minute)) || location.OccurredAt.After(now.Add(30*time.Second)) {
		return nil, nil
	}
	tx, err := s.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var previousAt sql.NullString
	if err = tx.QueryRowContext(ctx, `SELECT last_located_at FROM users WHERE id=?`, user.ID).Scan(&previousAt); err != nil {
		return nil, err
	}
	if previous := store.NullTime(previousAt); previous != nil && !location.OccurredAt.After(*previous) {
		return nil, nil
	}
	location.ID, location.UserID = uuid.NewString(), user.ID
	if _, err = tx.ExecContext(ctx, `UPDATE users SET last_latitude=?,last_longitude=?,last_accuracy=?,last_located_at=?,updated_at=? WHERE id=?`, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano), user.ID); err != nil {
		return nil, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT id,name,latitude,longitude,radius_meters,enabled,created_at,updated_at FROM places WHERE enabled=1`)
	if err != nil {
		return nil, err
	}
	var transitions []UserTransition
	for rows.Next() {
		place, scanErr := scanPlace(rows)
		if scanErr != nil {
			rows.Close()
			return nil, scanErr
		}

		inside, changed, membershipErr := updateMembership(ctx, tx, "user", user.ID, "user_place_memberships", "user_id", place, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt)
		if membershipErr != nil {
			rows.Close()
			return nil, membershipErr
		}
		if changed {
			transitions = append(transitions, UserTransition{User: user, Place: place, Entered: inside, At: location.OccurredAt})
		}

	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()
	keep, err := retainHistoryPoint(ctx, tx, "user_locations", "user_id", user.ID, location.Latitude, location.Longitude, location.Accuracy)
	if err != nil {
		return nil, err
	}
	if keep {
		var cached string
		cacheErr := tx.QueryRowContext(ctx, `SELECT address FROM location_address_cache WHERE cell=printf('%.4f,%.4f',?,?) AND address!=''`, location.Latitude, location.Longitude).Scan(&cached)
		if cacheErr != nil && cacheErr != sql.ErrNoRows {
			return nil, cacheErr
		}
		location.Address = cached
		if _, err = tx.ExecContext(ctx, `INSERT INTO user_locations(id,user_id,latitude,longitude,accuracy,address,occurred_at,created_at) VALUES(?,?,?,?,?,?,?,?)`, location.ID, location.UserID, location.Latitude, location.Longitude, location.Accuracy, location.Address, location.OccurredAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano)); err != nil {
			return nil, err
		}
	} else if location.Accuracy > 0 && location.Accuracy <= historyAccuracy {
		if _, err = tx.ExecContext(ctx, `UPDATE user_locations SET last_seen_at=? WHERE id=(SELECT id FROM user_locations WHERE user_id=? AND accuracy>0 AND accuracy<=50 ORDER BY occurred_at DESC LIMIT 1)`, location.OccurredAt.Format(time.RFC3339Nano), user.ID); err != nil {
			return nil, err
		}
	}

	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return transitions, nil
}

func (s *Service) ListPeople(ctx context.Context) ([]Person, error) {
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id,name,color,device_id,enabled,last_latitude,last_longitude,last_accuracy,last_located_at,created_at,updated_at FROM people ORDER BY name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Person
	for rows.Next() {
		p, err := scanPerson(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Service) GetPerson(ctx context.Context, id string) (Person, error) {
	return scanPerson(s.store.DB.QueryRowContext(ctx, `SELECT id,name,color,device_id,enabled,last_latitude,last_longitude,last_accuracy,last_located_at,created_at,updated_at FROM people WHERE id=?`, id))
}

func (s *Service) CreatePerson(ctx context.Context, p Person, deviceID string) (Person, error) {
	if p.Name == "" {
		return Person{}, fmt.Errorf("person name is required")
	}
	if p.Color == "" {
		p.Color = "#5B5BD6"
	}
	p.ID, p.DeviceID, p.Enabled = uuid.NewString(), deviceID, true
	p.CreatedAt, p.UpdatedAt = time.Now().UTC(), time.Now().UTC()
	_, err := s.store.DB.ExecContext(ctx, `INSERT INTO people(id,name,color,device_id,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?)`, p.ID, p.Name, p.Color, p.DeviceID, 1, p.CreatedAt.Format(time.RFC3339Nano), p.UpdatedAt.Format(time.RFC3339Nano))
	return p, err
}

func (s *Service) UpdatePerson(ctx context.Context, id string, p Person) (Person, error) {
	if p.Name == "" {
		return Person{}, fmt.Errorf("person name is required")
	}
	if p.Color == "" {
		p.Color = "#5B5BD6"
	}
	now := s.now().UTC()
	result, err := s.store.DB.ExecContext(ctx, `UPDATE people SET name=?,color=?,enabled=?,updated_at=? WHERE id=?`, p.Name, p.Color, boolInt(p.Enabled), now.Format(time.RFC3339Nano), id)
	if err != nil {
		return Person{}, err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return Person{}, sql.ErrNoRows
	}
	return s.GetPerson(ctx, id)
}

func (s *Service) DeletePerson(ctx context.Context, id string) error {
	result, err := s.store.DB.ExecContext(ctx, `DELETE FROM people WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func (s *Service) ListPlaces(ctx context.Context) ([]Place, error) {
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id,name,latitude,longitude,radius_meters,enabled,created_at,updated_at FROM places ORDER BY name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Place
	for rows.Next() {
		p, err := scanPlace(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Service) CreatePlace(ctx context.Context, p Place) (Place, error) {
	if p.Name == "" {
		return Place{}, fmt.Errorf("place name is required")
	}
	if !validCoordinate(p.Latitude, p.Longitude) || p.RadiusMeters < 20 || p.RadiusMeters > 5000 {
		return Place{}, fmt.Errorf("place coordinates or radius are invalid")
	}
	p.ID = uuid.NewString()
	p.Enabled = true
	p.CreatedAt = time.Now().UTC()
	p.UpdatedAt = p.CreatedAt
	_, err := s.store.DB.ExecContext(ctx, `INSERT INTO places(id,name,latitude,longitude,radius_meters,enabled,created_at,updated_at)VALUES(?,?,?,?,?,?,?,?)`, p.ID, p.Name, p.Latitude, p.Longitude, p.RadiusMeters, 1, p.CreatedAt.Format(time.RFC3339Nano), p.UpdatedAt.Format(time.RFC3339Nano))
	return p, err
}

func (s *Service) UpdatePlace(ctx context.Context, id string, p Place) (Place, error) {
	if p.Name == "" {
		return Place{}, fmt.Errorf("place name is required")
	}
	if !validCoordinate(p.Latitude, p.Longitude) || p.RadiusMeters < 20 || p.RadiusMeters > 5000 {
		return Place{}, fmt.Errorf("place coordinates or radius are invalid")
	}
	now := s.now().UTC()
	result, err := s.store.DB.ExecContext(ctx, `UPDATE places SET name=?,latitude=?,longitude=?,radius_meters=?,enabled=?,updated_at=? WHERE id=?`, p.Name, p.Latitude, p.Longitude, p.RadiusMeters, boolInt(p.Enabled), now.Format(time.RFC3339Nano), id)
	if err != nil {
		return Place{}, err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return Place{}, sql.ErrNoRows
	}
	return s.place(ctx, id)
}

func (s *Service) place(ctx context.Context, id string) (Place, error) {
	return scanPlace(s.store.DB.QueryRowContext(ctx, `SELECT id,name,latitude,longitude,radius_meters,enabled,created_at,updated_at FROM places WHERE id=?`, id))
}
func (s *Service) DeletePlace(ctx context.Context, id string) error {
	result, err := s.store.DB.ExecContext(ctx, `DELETE FROM places WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func (s *Service) History(ctx context.Context, personID string, limit int) ([]Location, error) {
	if limit < 1 || limit > 500 {
		limit = 100
	}
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id,person_id,latitude,longitude,accuracy,occurred_at FROM person_locations WHERE person_id=? ORDER BY occurred_at DESC LIMIT ?`, personID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Location
	for rows.Next() {
		var l Location
		var at string
		if err = rows.Scan(&l.ID, &l.PersonID, &l.Latitude, &l.Longitude, &l.Accuracy, &at); err != nil {
			return nil, err
		}
		l.OccurredAt, _ = time.Parse(time.RFC3339Nano, at)
		out = append(out, l)
	}
	return out, rows.Err()
}

func (s *Service) Report(ctx context.Context, personID, reporter string, location Location) ([]Transition, error) {
	if !validCoordinate(location.Latitude, location.Longitude) || math.IsNaN(location.Accuracy) || math.IsInf(location.Accuracy, 0) || location.Accuracy < 0 || location.Accuracy > 10000 {
		return nil, fmt.Errorf("location is invalid")
	}
	person, err := s.GetPerson(ctx, personID)
	if err != nil {
		return nil, err
	}
	if !person.Enabled {
		return nil, fmt.Errorf("person tracking is disabled")
	}
	if person.DeviceID != "" && person.DeviceID != reporter {
		return nil, fmt.Errorf("this device is not linked to the selected person")
	}
	now := s.now().UTC()
	if location.OccurredAt.IsZero() {
		location.OccurredAt = now
	}
	if location.OccurredAt.Before(now.Add(-24*time.Hour)) || location.OccurredAt.After(now.Add(5*time.Minute)) {
		return nil, fmt.Errorf("location timestamp is invalid")
	}
	// A stale provider cache is not a new location observation.
	if location.OccurredAt.Before(now.Add(-2*time.Minute)) || location.OccurredAt.After(now.Add(30*time.Second)) {
		return nil, nil
	}
	tx, err := s.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var previousAt sql.NullString
	if err = tx.QueryRowContext(ctx, `SELECT last_located_at FROM people WHERE id=?`, personID).Scan(&previousAt); err != nil {
		return nil, err
	}
	if previous := store.NullTime(previousAt); previous != nil && !location.OccurredAt.After(*previous) {
		return nil, nil
	}
	location.ID = uuid.NewString()
	location.PersonID = personID
	_, err = tx.ExecContext(ctx, `UPDATE people SET last_latitude=?,last_longitude=?,last_accuracy=?,last_located_at=?,updated_at=? WHERE id=?`, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano), personID)
	if err != nil {
		return nil, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT id,name,latitude,longitude,radius_meters,enabled,created_at,updated_at FROM places WHERE enabled=1`)
	if err != nil {
		return nil, err
	}
	var transitions []Transition
	for rows.Next() {
		place, scanErr := scanPlace(rows)
		if scanErr != nil {
			rows.Close()
			return nil, scanErr
		}

		inside, changed, membershipErr := updateMembership(ctx, tx, "person", personID, "place_memberships", "person_id", place, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt)
		if membershipErr != nil {
			rows.Close()
			return nil, membershipErr
		}
		if changed {
			transitions = append(transitions, Transition{Person: person, Place: place, Entered: inside, At: location.OccurredAt})
		}

	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()
	keep, err := retainHistoryPoint(ctx, tx, "person_locations", "person_id", personID, location.Latitude, location.Longitude, location.Accuracy)
	if err != nil {
		return nil, err
	}
	if keep || len(transitions) > 0 {
		_, err = tx.ExecContext(ctx, `INSERT INTO person_locations(id,person_id,latitude,longitude,accuracy,occurred_at,created_at)VALUES(?,?,?,?,?,?,?)`, location.ID, personID, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
		if err != nil {
			return nil, err
		}
	}

	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return transitions, nil
}

type scanner interface{ Scan(...any) error }

func scanUser(row scanner) (User, error) {
	var user User
	var enabled int
	var lat, lon, accuracy sql.NullFloat64
	var located sql.NullString
	var created, updated string
	err := row.Scan(&user.ID, &user.Name, &user.Color, &user.AvatarData, &enabled, &lat, &lon, &accuracy, &located, &created, &updated)
	if err != nil {
		return user, err
	}
	user.Enabled = enabled == 1
	if lat.Valid {
		user.LastLatitude = &lat.Float64
	}
	if lon.Valid {
		user.LastLongitude = &lon.Float64
	}
	if accuracy.Valid {
		user.LastAccuracy = &accuracy.Float64
	}
	user.LastLocatedAt = store.NullTime(located)
	user.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	user.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	return user, nil
}

func validAvatarData(value string) bool {
	const prefix = "data:image/jpeg;base64,"
	if !strings.HasPrefix(value, prefix) || len(value) > 300_000 {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(value, prefix))
	return err == nil && len(decoded) > 0 && len(decoded) <= 220_000
}
func scanPerson(row scanner) (Person, error) {
	var p Person
	var enabled int
	var lat, lon, accuracy sql.NullFloat64
	var located sql.NullString
	var created, updated string
	err := row.Scan(&p.ID, &p.Name, &p.Color, &p.DeviceID, &enabled, &lat, &lon, &accuracy, &located, &created, &updated)
	if err != nil {
		return p, err
	}
	p.Enabled = enabled == 1
	if lat.Valid {
		p.LastLatitude = &lat.Float64
	}
	if lon.Valid {
		p.LastLongitude = &lon.Float64
	}
	if accuracy.Valid {
		p.LastAccuracy = &accuracy.Float64
	}
	p.LastLocatedAt = store.NullTime(located)
	p.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	p.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	return p, nil
}
func scanPlace(row scanner) (Place, error) {
	var p Place
	var enabled int
	var created, updated string
	err := row.Scan(&p.ID, &p.Name, &p.Latitude, &p.Longitude, &p.RadiusMeters, &enabled, &created, &updated)
	if err != nil {
		return p, err
	}
	p.Enabled = enabled == 1
	p.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	p.UpdatedAt, _ = time.Parse(time.RFC3339Nano, updated)
	return p, nil
}
func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
func validCoordinate(lat, lon float64) bool {
	return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
}
func distanceMeters(aLat, aLon, bLat, bLon float64) float64 {
	const earth = 6371000.0
	dLat := (bLat - aLat) * math.Pi / 180
	dLon := (bLon - aLon) * math.Pi / 180
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(aLat*math.Pi/180)*math.Cos(bLat*math.Pi/180)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return earth * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
}

// Only change membership when the accuracy circle is fully on one side of the
// boundary. A minimum margin prevents GPS jitter from alternating enter/exit.
func confidentMembership(latitude, longitude, accuracy float64, place Place) (inside, certain bool) {
	if accuracy <= 0 || accuracy > 200 || math.IsNaN(accuracy) || math.IsInf(accuracy, 0) {
		return false, false
	}
	distance := distanceMeters(latitude, longitude, place.Latitude, place.Longitude)
	margin := math.Max(accuracy, 15)
	if distance+margin < place.RadiusMeters {
		return true, true
	}
	if distance-margin > place.RadiusMeters {
		return false, true
	}
	return false, false
}

// Compare against the last retained point, not the latest heartbeat: a slow
// journey must eventually accumulate enough distance to appear in history.
func retainHistoryPoint(ctx context.Context, tx *sql.Tx, table, owner, id string, lat, lon, accuracy float64) (bool, error) {
	if accuracy <= 0 || accuracy > historyAccuracy {
		return false, nil
	}
	var previousLat, previousLon, previousAccuracy float64
	err := tx.QueryRowContext(ctx, "SELECT latitude,longitude,accuracy FROM "+table+" WHERE "+owner+"=? AND accuracy>0 AND accuracy<=? ORDER BY occurred_at DESC LIMIT 1", id, historyAccuracy).Scan(&previousLat, &previousLon, &previousAccuracy)
	if err == sql.ErrNoRows {
		return true, nil
	}
	if err != nil {
		return false, err
	}
	threshold := math.Max(historyDistance, 2*(previousAccuracy+accuracy))
	return distanceMeters(previousLat, previousLon, lat, lon) >= threshold, nil
}

// Require three independent observations spanning two minutes. Uncertain fixes
// cancel confirmation. Persist candidates so restarts cannot bypass the dwell.
func updateMembership(ctx context.Context, tx *sql.Tx, kind, owner, table, column string, place Place, lat, lon, accuracy float64, at time.Time) (bool, bool, error) {
	inside, certain := confidentMembership(lat, lon, accuracy, place)
	clear := func() error {
		_, err := tx.ExecContext(ctx, `DELETE FROM geofence_candidates WHERE owner_kind=? AND owner_id=? AND place_id=?`, kind, owner, place.ID)
		return err
	}
	if !certain {
		return false, false, clear()
	}
	var previous int
	err := tx.QueryRowContext(ctx, "SELECT inside FROM "+table+" WHERE "+column+"=? AND place_id=?", owner, place.ID).Scan(&previous)
	stamp := at.UTC().Format(time.RFC3339Nano)
	if err == sql.ErrNoRows {
		_, err = tx.ExecContext(ctx, "INSERT INTO "+table+"("+column+",place_id,inside,updated_at) VALUES(?,?,?,?)", owner, place.ID, boolInt(inside), stamp)
		return inside, false, err
	}
	if err != nil {
		return inside, false, err
	}
	if (previous == 1) == inside {
		return inside, false, clear()
	}
	var target, samples int
	var since, last, version string
	err = tx.QueryRowContext(ctx, `SELECT inside,since_at,last_at,samples,place_version FROM geofence_candidates WHERE owner_kind=? AND owner_id=? AND place_id=?`, kind, owner, place.ID).Scan(&target, &since, &last, &samples, &version)
	if err != nil && err != sql.ErrNoRows {
		return inside, false, err
	}
	sinceAt, _ := time.Parse(time.RFC3339Nano, since)
	lastAt, _ := time.Parse(time.RFC3339Nano, last)
	currentVersion := place.UpdatedAt.Format(time.RFC3339Nano)
	if err == sql.ErrNoRows || target != boolInt(inside) || version != currentVersion || at.Sub(lastAt) > 3*time.Minute {
		sinceAt, samples = at, 1
	} else {
		if at.Sub(lastAt) < 20*time.Second {
			return inside, false, nil
		}
		samples++
	}
	if samples >= 3 && at.Sub(sinceAt) >= 2*time.Minute {
		_, err = tx.ExecContext(ctx, "UPDATE "+table+" SET inside=?,updated_at=? WHERE "+column+"=? AND place_id=?", boolInt(inside), stamp, owner, place.ID)
		if err != nil {
			return inside, false, err
		}
		return inside, true, clear()
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO geofence_candidates(owner_kind,owner_id,place_id,inside,since_at,last_at,samples,place_version) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(owner_kind,owner_id,place_id) DO UPDATE SET inside=excluded.inside,since_at=excluded.since_at,last_at=excluded.last_at,samples=excluded.samples,place_version=excluded.place_version`, kind, owner, place.ID, boolInt(inside), sinceAt.UTC().Format(time.RFC3339Nano), stamp, samples, currentVersion)
	return inside, false, err
}

// PendingConfirmations lets the phone keep reporting while a boundary change
// is being confirmed, including slow crossings near the end of a sampling burst.
func (s *Service) PendingConfirmations(ctx context.Context, deviceID string) (int, error) {
	var count int
	err := s.store.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM geofence_candidates c JOIN devices d ON d.user_id=c.owner_id JOIN places p ON p.id=c.place_id WHERE c.owner_kind='user' AND d.id=? AND d.enabled=1 AND p.enabled=1 AND c.last_at>=?`, deviceID, s.now().UTC().Add(-3*time.Minute).Format(time.RFC3339Nano)).Scan(&count)
	return count, err
}
