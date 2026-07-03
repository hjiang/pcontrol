package store

import (
	"database/sql"
	"fmt"

	"pcontrol/server/internal/domain"
)

// SetLimit creates or updates a per-subject limit for a device.
func (s *Store) SetLimit(deviceID int64, kind domain.Kind, subject string, minutes int) (domain.Limit, error) {
	tx, err := s.DB.Begin()
	if err != nil {
		return domain.Limit{}, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	// upsert limit
	res, err := tx.Exec(`
		INSERT INTO limits (device_id, kind, subject, daily_limit_minutes)
		VALUES (?, ?, ?, ?)
		ON CONFLICT(device_id, kind, subject) DO UPDATE SET daily_limit_minutes = excluded.daily_limit_minutes
	`, deviceID, string(kind), subject, minutes)
	if err != nil {
		return domain.Limit{}, fmt.Errorf("insert limit: %w", err)
	}

	if err := bumpPolicyVersion(tx, deviceID); err != nil {
		return domain.Limit{}, err
	}

	id, _ := res.LastInsertId()
	// For ON CONFLICT UPDATE, LastInsertId might be 0 on some drivers; fetch it
	if id == 0 {
		row := tx.QueryRow(`SELECT id FROM limits WHERE device_id = ? AND kind = ? AND subject = ?`, deviceID, string(kind), subject)
		row.Scan(&id)
	}

	if err := tx.Commit(); err != nil {
		return domain.Limit{}, fmt.Errorf("commit: %w", err)
	}

	return domain.Limit{ID: id, DeviceID: deviceID, Kind: kind, Subject: subject, DailyLimitMinutes: minutes}, nil
}

// DeleteLimit removes a limit by ID.
func (s *Store) DeleteLimit(limitID int64) error {
	return s.mutateAndBump(func(tx *sql.Tx) error {
		// Get device_id before deleting
		row := tx.QueryRow(`SELECT device_id FROM limits WHERE id = ?`, limitID)
		var deviceID int64
		if err := row.Scan(&deviceID); err != nil {
			return fmt.Errorf("find limit: %w", err)
		}

		if _, err := tx.Exec(`DELETE FROM limits WHERE id = ?`, limitID); err != nil {
			return err
		}
		return bumpPolicyVersion(tx, deviceID)
	})
}

// AddExclusion adds a new exclusion for a device.
func (s *Store) AddExclusion(deviceID int64, kind domain.Kind, subject string) (domain.Exclusion, error) {
	tx, err := s.DB.Begin()
	if err != nil {
		return domain.Exclusion{}, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	res, err := tx.Exec(`
		INSERT INTO exclusions (device_id, kind, subject)
		VALUES (?, ?, ?)
		ON CONFLICT(device_id, kind, subject) DO NOTHING
	`, deviceID, string(kind), subject)
	if err != nil {
		return domain.Exclusion{}, fmt.Errorf("insert exclusion: %w", err)
	}

	if err := bumpPolicyVersion(tx, deviceID); err != nil {
		return domain.Exclusion{}, err
	}

	id, _ := res.LastInsertId()
	if id == 0 {
		row := tx.QueryRow(`SELECT id FROM exclusions WHERE device_id = ? AND kind = ? AND subject = ?`, deviceID, string(kind), subject)
		row.Scan(&id)
	}

	if err := tx.Commit(); err != nil {
		return domain.Exclusion{}, fmt.Errorf("commit: %w", err)
	}

	return domain.Exclusion{ID: id, DeviceID: deviceID, Kind: kind, Subject: subject}, nil
}

// DeleteExclusion removes an exclusion by ID.
func (s *Store) DeleteExclusion(exclusionID int64) error {
	return s.mutateAndBump(func(tx *sql.Tx) error {
		row := tx.QueryRow(`SELECT device_id FROM exclusions WHERE id = ?`, exclusionID)
		var deviceID int64
		if err := row.Scan(&deviceID); err != nil {
			return fmt.Errorf("find exclusion: %w", err)
		}

		if _, err := tx.Exec(`DELETE FROM exclusions WHERE id = ?`, exclusionID); err != nil {
			return err
		}
		return bumpPolicyVersion(tx, deviceID)
	})
}

// SetTotalLimit sets or clears the total daily limit. Pass nil to clear.
func (s *Store) SetTotalLimit(deviceID int64, totalMinutes *int) error {
	return s.mutateAndBump(func(tx *sql.Tx) error {
		ensureDeviceSettings(tx, deviceID)
		if totalMinutes != nil {
			if _, err := tx.Exec(`UPDATE device_settings SET total_daily_limit_minutes = ? WHERE device_id = ?`, *totalMinutes, deviceID); err != nil {
				return err
			}
		} else {
			if _, err := tx.Exec(`UPDATE device_settings SET total_daily_limit_minutes = NULL WHERE device_id = ?`, deviceID); err != nil {
				return err
			}
		}
		return bumpPolicyVersion(tx, deviceID)
	})
}

// SetWarnPercent sets the warn threshold percentage.
func (s *Store) SetWarnPercent(deviceID int64, percent int) error {
	return s.mutateAndBump(func(tx *sql.Tx) error {
		ensureDeviceSettings(tx, deviceID)
		if _, err := tx.Exec(`UPDATE device_settings SET warn_threshold_percent = ? WHERE device_id = ?`, percent, deviceID); err != nil {
			return err
		}
		return bumpPolicyVersion(tx, deviceID)
	})
}

// GetPolicy returns the full policy for a device.
func (s *Store) GetPolicy(deviceID int64) (domain.Policy, error) {
	policy := domain.Policy{
		WarnThresholdPercent: 90, // default
	}

	// Device settings
	row := s.DB.QueryRow(`SELECT total_daily_limit_minutes, warn_threshold_percent, policy_version FROM device_settings WHERE device_id = ?`, deviceID)
	var totalLimit sql.NullInt64
	if err := row.Scan(&totalLimit, &policy.WarnThresholdPercent, &policy.Version); err == sql.ErrNoRows {
		// No settings yet — use defaults
		policy.Version = 1
	} else if err != nil {
		return policy, fmt.Errorf("get device settings: %w", err)
	} else if totalLimit.Valid {
		v := int(totalLimit.Int64)
		policy.TotalDailyLimitMin = &v
	}

	// Limits
	limitRows, err := s.DB.Query(`SELECT id, kind, subject, daily_limit_minutes FROM limits WHERE device_id = ?`, deviceID)
	if err != nil {
		return policy, fmt.Errorf("query limits: %w", err)
	}
	defer limitRows.Close()
	for limitRows.Next() {
		var l domain.Limit
		var kindStr string
		if err := limitRows.Scan(&l.ID, &kindStr, &l.Subject, &l.DailyLimitMinutes); err != nil {
			return policy, fmt.Errorf("scan limit: %w", err)
		}
		l.Kind = domain.Kind(kindStr)
		l.DeviceID = deviceID
		policy.Limits = append(policy.Limits, l)
	}

	// Exclusions
	excRows, err := s.DB.Query(`SELECT id, kind, subject FROM exclusions WHERE device_id = ?`, deviceID)
	if err != nil {
		return policy, fmt.Errorf("query exclusions: %w", err)
	}
	defer excRows.Close()
	for excRows.Next() {
		var e domain.Exclusion
		var kindStr string
		if err := excRows.Scan(&e.ID, &kindStr, &e.Subject); err != nil {
			return policy, fmt.Errorf("scan exclusion: %w", err)
		}
		e.Kind = domain.Kind(kindStr)
		e.DeviceID = deviceID
		policy.Exclusions = append(policy.Exclusions, e)
	}

	return policy, nil
}

// PolicyVersion returns the current policy version for a device.
func (s *Store) PolicyVersion(deviceID int64) (int, error) {
	row := s.DB.QueryRow(`SELECT COALESCE((SELECT policy_version FROM device_settings WHERE device_id = ?), 1)`, deviceID)
	var v int
	if err := row.Scan(&v); err != nil {
		return 0, err
	}
	return v, nil
}

// --- helpers ---

// mutateAndBump runs a mutation in a transaction and bumps the policy version.
func (s *Store) mutateAndBump(fn func(*sql.Tx) error) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	if err := fn(tx); err != nil {
		return err
	}

	return tx.Commit()
}

// bumpPolicyVersion increments policy_version for a device inside a transaction.
func bumpPolicyVersion(tx *sql.Tx, deviceID int64) error {
	ensureDeviceSettings(tx, deviceID)
	_, err := tx.Exec(`UPDATE device_settings SET policy_version = policy_version + 1 WHERE device_id = ?`, deviceID)
	return err
}

func ensureDeviceSettings(tx *sql.Tx, deviceID int64) {
	tx.Exec(`INSERT OR IGNORE INTO device_settings (device_id) VALUES (?)`, deviceID)
}
