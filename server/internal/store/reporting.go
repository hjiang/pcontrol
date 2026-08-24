package store

import (
	"database/sql"
	"fmt"
	"strings"
	"time"

	"pcontrol/server/internal/domain"
)

// SetEmailRecipients replaces the daily-report recipient list for a device.
// Empty entries are dropped and duplicates are collapsed case-insensitively
// (emails are stored lowercased) while preserving the given order.
func (s *Store) SetEmailRecipients(deviceID int64, emails []string) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`DELETE FROM device_email_recipients WHERE device_id = ?`, deviceID); err != nil {
		return fmt.Errorf("delete recipients: %w", err)
	}

	seen := make(map[string]bool)
	position := 0
	for _, email := range emails {
		e := strings.ToLower(strings.TrimSpace(email))
		if e == "" || seen[e] {
			continue
		}
		seen[e] = true
		if _, err := tx.Exec(
			`INSERT INTO device_email_recipients (device_id, email, position) VALUES (?, ?, ?)`,
			deviceID, e, position); err != nil {
			return fmt.Errorf("insert recipient: %w", err)
		}
		position++
	}

	return tx.Commit()
}

// EmailRecipients returns the daily-report recipients for a device in
// configured order.
func (s *Store) EmailRecipients(deviceID int64) ([]string, error) {
	rows, err := s.DB.Query(
		`SELECT email FROM device_email_recipients WHERE device_id = ? ORDER BY position, rowid`,
		deviceID)
	if err != nil {
		return nil, fmt.Errorf("query recipients: %w", err)
	}
	defer rows.Close()

	var emails []string
	for rows.Next() {
		var e string
		if err := rows.Scan(&e); err != nil {
			return nil, fmt.Errorf("scan recipient: %w", err)
		}
		emails = append(emails, e)
	}
	return emails, rows.Err()
}

// SetTimeZone sets the report timezone for a device. An empty string clears
// it (the report then uses UTC). Unlike policy settings, this does not bump
// policy_version because the timezone is not part of the synced policy.
func (s *Store) SetTimeZone(deviceID int64, tz string) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	ensureDeviceSettings(tx, deviceID)
	if _, err := tx.Exec(`UPDATE device_settings SET timezone = ? WHERE device_id = ?`, tz, deviceID); err != nil {
		return fmt.Errorf("set timezone: %w", err)
	}
	return tx.Commit()
}

// TimeZone returns the configured report timezone for a device ("" if unset).
func (s *Store) TimeZone(deviceID int64) (string, error) {
	var tz sql.NullString
	err := s.DB.QueryRow(`SELECT timezone FROM device_settings WHERE device_id = ?`, deviceID).Scan(&tz)
	if err == sql.ErrNoRows {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("get timezone: %w", err)
	}
	if !tz.Valid {
		return "", nil
	}
	return tz.String, nil
}

// ReportTargets returns every device that has at least one email recipient,
// together with its name and configured report timezone ("" when unset).
func (s *Store) ReportTargets() ([]domain.ReportTarget, error) {
	rows, err := s.DB.Query(`
		SELECT d.id, d.name, COALESCE(ds.timezone, '')
		FROM devices d
		JOIN device_email_recipients r ON r.device_id = d.id
		LEFT JOIN device_settings ds ON ds.device_id = d.id
		GROUP BY d.id
		ORDER BY d.id`)
	if err != nil {
		return nil, fmt.Errorf("query report targets: %w", err)
	}
	defer rows.Close()

	var targets []domain.ReportTarget
	for rows.Next() {
		var t domain.ReportTarget
		if err := rows.Scan(&t.DeviceID, &t.Name, &t.TimeZone); err != nil {
			return nil, fmt.Errorf("scan report target: %w", err)
		}
		targets = append(targets, t)
	}
	return targets, rows.Err()
}

// ReportSent reports whether the daily report for (device, day) has already
// been sent.
func (s *Store) ReportSent(deviceID int64, day string) (bool, error) {
	var n int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM daily_report_log WHERE device_id = ? AND day = ?`,
		deviceID, day).Scan(&n); err != nil {
		return false, fmt.Errorf("query report log: %w", err)
	}
	return n > 0, nil
}

// MarkReportSent records that the daily report for (device, day) was sent.
// Idempotent: a duplicate call is ignored.
func (s *Store) MarkReportSent(deviceID int64, day string, sentAt time.Time) error {
	_, err := s.DB.Exec(
		`INSERT OR IGNORE INTO daily_report_log (device_id, day, sent_at) VALUES (?, ?, ?)`,
		deviceID, day, sentAt.UTC().Format(time.RFC3339))
	if err != nil {
		return fmt.Errorf("insert report log: %w", err)
	}
	return nil
}
