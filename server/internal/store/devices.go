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
		`SELECT id, name, token_hash, created_at, last_seen_at,
		        battery_percent, battery_charging, battery_updated_at
		 FROM devices WHERE token_hash = ?`,
		hash,
	)
	return scanDevice(row)
}

func (s *Store) deviceByID(id int64) (domain.Device, error) {
	row := s.DB.QueryRow(
		`SELECT id, name, token_hash, created_at, last_seen_at,
		        battery_percent, battery_charging, battery_updated_at
		 FROM devices WHERE id = ?`,
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

// RenameDevice updates the name of a device. Returns sql.ErrNoRows if the
// device does not exist, or an error if name is empty.
func (s *Store) RenameDevice(deviceID int64, name string) error {
	if name == "" {
		return fmt.Errorf("device name cannot be empty")
	}
	res, err := s.DB.Exec(`UPDATE devices SET name = ? WHERE id = ?`, name, deviceID)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

// DeleteDevice removes a device and all its child rows (events, limits,
// exclusions, settings) in a single transaction. Returns sql.ErrNoRows if
// the device does not exist.
func (s *Store) DeleteDevice(deviceID int64) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return fmt.Errorf("delete device begin tx: %w", err)
	}
	defer tx.Rollback()

	type delStep struct {
		Label string
		SQL   string
	}
	for _, step := range []delStep{
		{"usage_events", `DELETE FROM usage_events WHERE device_id = ?`},
		{"limits", `DELETE FROM limits WHERE device_id = ?`},
		{"exclusions", `DELETE FROM exclusions WHERE device_id = ?`},
		{"device_settings", `DELETE FROM device_settings WHERE device_id = ?`},
		{"devices", `DELETE FROM devices WHERE id = ?`},
	} {
		res, err := tx.Exec(step.SQL, deviceID)
		if err != nil {
			return fmt.Errorf("delete %s: %w", step.Label, err)
		}
		// Check RowsAffected on the final DELETE FROM devices
		if step.Label == "devices" {
			n, err := res.RowsAffected()
			if err != nil {
				return err
			}
			if n == 0 {
				return sql.ErrNoRows
			}
		}
	}

	return tx.Commit()
}

// UpdateBatteryStatus updates the battery status for a device.
func (s *Store) UpdateBatteryStatus(deviceID int64, percent int, charging bool, t time.Time) error {
	chargingInt := 0
	if charging {
		chargingInt = 1
	}
	_, err := s.DB.Exec(
		`UPDATE devices SET battery_percent = ?, battery_charging = ?, battery_updated_at = ? WHERE id = ?`,
		percent, chargingInt, t.UTC().Format(time.RFC3339), deviceID)
	return err
}

// scanDevice scans a device row from the given row/s.
func scanDevice(scanner interface {
	Scan(dest ...interface{}) error
}) (domain.Device, error) {
	var d domain.Device
	var createdAt, lastSeenAt, batteryUpdatedAt sql.NullString
	var batteryPercent, batteryCharging sql.NullInt64
	err := scanner.Scan(&d.ID, &d.Name, &d.TokenHash, &createdAt, &lastSeenAt,
		&batteryPercent, &batteryCharging, &batteryUpdatedAt)
	if err != nil {
		return domain.Device{}, err
	}
	d.CreatedAt = parseTime(createdAt.String)
	if lastSeenAt.Valid {
		t := parseTime(lastSeenAt.String)
		d.LastSeenAt = &t
	}
	if batteryPercent.Valid {
		v := int(batteryPercent.Int64)
		d.BatteryPercent = &v
	}
	if batteryCharging.Valid {
		v := batteryCharging.Int64 != 0
		d.BatteryCharging = &v
	}
	if batteryUpdatedAt.Valid {
		t := parseTime(batteryUpdatedAt.String)
		d.BatteryUpdatedAt = &t
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
