package store

import (
	"fmt"

	"pcontrol/server/internal/domain"
)

// InsertEvents inserts usage events, silently ignoring duplicates by event_id.
func (s *Store) InsertEvents(events []domain.Event) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	stmt, err := tx.Prepare(`
		INSERT OR IGNORE INTO usage_events (event_id, device_id, kind, subject, label, day, started_at, duration_seconds)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)
	`)
	if err != nil {
		return fmt.Errorf("prepare: %w", err)
	}
	defer stmt.Close()

	for _, e := range events {
		if _, err := stmt.Exec(e.EventID, e.DeviceID, string(e.Kind), e.Subject, e.Label, e.Day, e.StartedAt.UTC().Format(iso8601), e.DurationSeconds); err != nil {
			return fmt.Errorf("insert event %s: %w", e.EventID, err)
		}
	}

	return tx.Commit()
}

const iso8601 = "2006-01-02T15:04:05Z07:00"

// UsageTotals returns aggregated usage totals for a device on a given day.
func (s *Store) UsageTotals(deviceID int64, day string) (appTotals, webTotals []domain.UsageTotal, err error) {
	rows, err := s.DB.Query(`
		SELECT kind, subject,
		       COALESCE((SELECT label FROM usage_events AS e2
		                 WHERE e2.kind = e.kind AND e2.subject = e.subject AND e2.label != ''
		                 ORDER BY e2.started_at DESC LIMIT 1), '') AS label,
		       SUM(duration_seconds) AS total_seconds
		FROM usage_events e
		WHERE device_id = ? AND day = ?
		GROUP BY kind, subject
		ORDER BY total_seconds DESC
	`, deviceID, day)
	if err != nil {
		return nil, nil, fmt.Errorf("query usage totals: %w", err)
	}
	defer rows.Close()

	for rows.Next() {
		var k, subject, label string
		var seconds int
		if err := rows.Scan(&k, &subject, &label, &seconds); err != nil {
			return nil, nil, fmt.Errorf("scan usage total: %w", err)
		}
		ut := domain.UsageTotal{Kind: domain.Kind(k), Subject: subject, Label: label, Seconds: seconds}
		switch ut.Kind {
		case domain.KindApp:
			appTotals = append(appTotals, ut)
		case domain.KindWeb:
			webTotals = append(webTotals, ut)
		}
	}
	return appTotals, webTotals, rows.Err()
}
