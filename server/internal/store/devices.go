package store

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"fmt"
	"time"

	"pcontrol/server/internal/domain"
)

// CreateDevice registers a new device, generates a raw bearer token, and
// returns the device record along with the raw token (shown only once).
func (s *Store) CreateDevice(name string) (domain.Device, string, error) {
	// Generate 32 random bytes for the bearer token
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		return domain.Device{}, "", fmt.Errorf("generate token: %w", err)
	}
	rawToken := base64url(tokenBytes)

	hash := sha256Hex(rawToken)
	now := time.Now().UTC().Format(time.RFC3339)

	res, err := s.DB.Exec(
		`INSERT INTO devices (name, token_hash, created_at) VALUES (?, ?, ?)`,
		name, hash, now,
	)
	if err != nil {
		return domain.Device{}, "", fmt.Errorf("insert device: %w", err)
	}

	id, _ := res.LastInsertId()
	return domain.Device{
		ID:        id,
		Name:      name,
		TokenHash: hash,
		CreatedAt: parseTime(now),
	}, rawToken, nil
}

// DeviceByToken looks up a device by its raw bearer token (hashed for lookup).
func (s *Store) DeviceByToken(rawToken string) (domain.Device, error) {
	hash := sha256Hex(rawToken)
	return s.deviceByHash(hash)
}

// DeviceByTokenFromID looks up a device by its numeric ID.
func (s *Store) DeviceByTokenFromID(id int64) (domain.Device, error) {
	return s.deviceByID(id)
}

func (s *Store) deviceByHash(hash string) (domain.Device, error) {
	row := s.DB.QueryRow(
		`SELECT id, name, token_hash, created_at, last_seen_at FROM devices WHERE token_hash = ?`,
		hash,
	)
	return scanDevice(row)
}

func (s *Store) deviceByID(id int64) (domain.Device, error) {
	row := s.DB.QueryRow(
		`SELECT id, name, token_hash, created_at, last_seen_at FROM devices WHERE id = ?`,
		id,
	)
	return scanDevice(row)
}

// TouchLastSeen updates the last_seen_at timestamp for a device.
func (s *Store) TouchLastSeen(deviceID int64, t time.Time) error {
	_, err := s.DB.Exec(`UPDATE devices SET last_seen_at = ? WHERE id = ?`,
		t.UTC().Format(time.RFC3339), deviceID)
	return err
}

// scanDevice scans a device row from the given row/s.
func scanDevice(scanner interface {
	Scan(dest ...interface{}) error
}) (domain.Device, error) {
	var d domain.Device
	var createdAt, lastSeenAt sql.NullString
	err := scanner.Scan(&d.ID, &d.Name, &d.TokenHash, &createdAt, &lastSeenAt)
	if err != nil {
		return domain.Device{}, err
	}
	d.CreatedAt = parseTime(createdAt.String)
	if lastSeenAt.Valid {
		t := parseTime(lastSeenAt.String)
		d.LastSeenAt = &t
	}
	return d, nil
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return fmt.Sprintf("%x", h)
}

func base64url(b []byte) string {
	return base64.RawURLEncoding.EncodeToString(b)
}

func parseTime(s string) time.Time {
	t, _ := time.Parse(time.RFC3339, s)
	return t
}
