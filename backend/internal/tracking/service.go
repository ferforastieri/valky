package tracking

import (
	"context"
	"database/sql"
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

type Service struct{ store *store.Store }

func New(s *store.Store) *Service { return &Service{store: s} }

// User is a family profile. It deliberately has no device credential in its
// public representation: a device belongs to a user, not the other way round.
type User struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Color         string     `json:"color"`
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
	OccurredAt time.Time `json:"occurredAt"`
}

type UserTransition struct {
	User    User
	Place   Place
	Entered bool
	At      time.Time
}

func (s *Service) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id,name,color,enabled,last_latitude,last_longitude,last_accuracy,last_located_at,created_at,updated_at FROM users WHERE enabled=1 ORDER BY name COLLATE NOCASE`)
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
	return scanUser(s.store.DB.QueryRowContext(ctx, `SELECT u.id,u.name,u.color,u.enabled,u.last_latitude,u.last_longitude,u.last_accuracy,u.last_located_at,u.created_at,u.updated_at FROM users u JOIN devices d ON d.user_id=u.id WHERE d.id=? AND d.enabled=1`, deviceID))
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
	now := time.Now().UTC().Format(time.RFC3339Nano)
	result, err := s.store.DB.ExecContext(ctx, `UPDATE users SET name=?,color=?,updated_at=? WHERE id=?`, name, color, now, id)
	if err != nil {
		return User{}, err
	}
	if affected, _ := result.RowsAffected(); affected == 0 {
		return User{}, sql.ErrNoRows
	}
	return scanUser(s.store.DB.QueryRowContext(ctx, `SELECT id,name,color,enabled,last_latitude,last_longitude,last_accuracy,last_located_at,created_at,updated_at FROM users WHERE id=?`, id))
}

func (s *Service) UpdateCurrentUser(ctx context.Context, deviceID string, in User) (User, error) {
	current, err := s.CurrentUser(ctx, deviceID)
	if err != nil {
		return User{}, err
	}
	return s.UpdateUser(ctx, current.ID, in)
}

func (s *Service) UserHistory(ctx context.Context, userID string, limit int) ([]UserLocation, error) {
	if limit < 1 || limit > 500 {
		limit = 100
	}
	rows, err := s.store.DB.QueryContext(ctx, `SELECT id,user_id,latitude,longitude,accuracy,occurred_at FROM user_locations WHERE user_id=? ORDER BY occurred_at DESC LIMIT ?`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	locations := make([]UserLocation, 0)
	for rows.Next() {
		var location UserLocation
		var occurredAt string
		if err := rows.Scan(&location.ID, &location.UserID, &location.Latitude, &location.Longitude, &location.Accuracy, &occurredAt); err != nil {
			return nil, err
		}
		location.OccurredAt, _ = time.Parse(time.RFC3339Nano, occurredAt)
		locations = append(locations, location)
	}
	return locations, rows.Err()
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
	if !validCoordinate(location.Latitude, location.Longitude) || location.Accuracy < 0 || location.Accuracy > 10000 {
		return nil, fmt.Errorf("location is invalid")
	}
	now := time.Now().UTC()
	if location.OccurredAt.IsZero() {
		location.OccurredAt = now
	}
	if location.OccurredAt.Before(now.Add(-24*time.Hour)) || location.OccurredAt.After(now.Add(5*time.Minute)) {
		return nil, fmt.Errorf("location timestamp is invalid")
	}
	tx, err := s.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	location.ID, location.UserID = uuid.NewString(), user.ID
	if _, err = tx.ExecContext(ctx, `INSERT INTO user_locations(id,user_id,latitude,longitude,accuracy,occurred_at,created_at) VALUES(?,?,?,?,?,?,?)`, location.ID, location.UserID, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano)); err != nil {
		return nil, err
	}
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
		inside := distanceMeters(location.Latitude, location.Longitude, place.Latitude, place.Longitude) <= place.RadiusMeters
		var previous int
		lookupErr := tx.QueryRowContext(ctx, `SELECT inside FROM user_place_memberships WHERE user_id=? AND place_id=?`, user.ID, place.ID).Scan(&previous)
		if lookupErr == sql.ErrNoRows {
			if _, scanErr = tx.ExecContext(ctx, `INSERT INTO user_place_memberships(user_id,place_id,inside,updated_at) VALUES(?,?,?,?)`, user.ID, place.ID, boolInt(inside), now.Format(time.RFC3339Nano)); scanErr != nil {
				rows.Close()
				return nil, scanErr
			}
		} else if lookupErr != nil {
			rows.Close()
			return nil, lookupErr
		} else if (previous == 1) != inside {
			if _, scanErr = tx.ExecContext(ctx, `UPDATE user_place_memberships SET inside=?,updated_at=? WHERE user_id=? AND place_id=?`, boolInt(inside), now.Format(time.RFC3339Nano), user.ID, place.ID); scanErr != nil {
				rows.Close()
				return nil, scanErr
			}
			transitions = append(transitions, UserTransition{User: user, Place: place, Entered: inside, At: location.OccurredAt})
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()
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
	now := time.Now().UTC()
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
	now := time.Now().UTC()
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
	if !validCoordinate(location.Latitude, location.Longitude) || location.Accuracy < 0 || location.Accuracy > 10000 {
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
	now := time.Now().UTC()
	if location.OccurredAt.IsZero() {
		location.OccurredAt = now
	}
	if location.OccurredAt.Before(now.Add(-24*time.Hour)) || location.OccurredAt.After(now.Add(5*time.Minute)) {
		return nil, fmt.Errorf("location timestamp is invalid")
	}
	tx, err := s.store.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	location.ID = uuid.NewString()
	location.PersonID = personID
	_, err = tx.ExecContext(ctx, `INSERT INTO person_locations(id,person_id,latitude,longitude,accuracy,occurred_at,created_at)VALUES(?,?,?,?,?,?,?)`, location.ID, personID, location.Latitude, location.Longitude, location.Accuracy, location.OccurredAt.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	if err != nil {
		return nil, err
	}
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
		inside := distanceMeters(location.Latitude, location.Longitude, place.Latitude, place.Longitude) <= place.RadiusMeters
		var previous int
		lookupErr := tx.QueryRowContext(ctx, `SELECT inside FROM place_memberships WHERE person_id=? AND place_id=?`, personID, place.ID).Scan(&previous)
		if lookupErr == sql.ErrNoRows {
			_, scanErr = tx.ExecContext(ctx, `INSERT INTO place_memberships(person_id,place_id,inside,updated_at)VALUES(?,?,?,?)`, personID, place.ID, boolInt(inside), now.Format(time.RFC3339Nano))
			if scanErr != nil {
				rows.Close()
				return nil, scanErr
			}
		} else if lookupErr != nil {
			rows.Close()
			return nil, lookupErr
		} else if (previous == 1) != inside {
			_, scanErr = tx.ExecContext(ctx, `UPDATE place_memberships SET inside=?,updated_at=? WHERE person_id=? AND place_id=?`, boolInt(inside), now.Format(time.RFC3339Nano), personID, place.ID)
			if scanErr != nil {
				rows.Close()
				return nil, scanErr
			}
			transitions = append(transitions, Transition{Person: person, Place: place, Entered: inside, At: location.OccurredAt})
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()
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
	err := row.Scan(&user.ID, &user.Name, &user.Color, &enabled, &lat, &lon, &accuracy, &located, &created, &updated)
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
