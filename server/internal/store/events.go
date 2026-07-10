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

// DistinctSubjects returns all distinct (kind, subject, label) triples ever
// seen for a device, ordered by most-recently-seen first.  Used to populate
// the <datalist> on the add-limit form.
func (s *Store) DistinctSubjects(deviceID int64) ([]domain.UsageTotal, error) {
	rows, err := s.DB.Query(`
		SELECT kind, subject,
		       COALESCE((SELECT label FROM usage_events AS e2
		                 WHERE e2.kind = e.kind AND e2.subject = e.subject AND e2.label != ''
		                 ORDER BY e2.started_at DESC LIMIT 1), '') AS label,
		       0 AS total_seconds
		FROM usage_events e
		WHERE device_id = ?
		GROUP BY kind, subject
		ORDER BY MAX(e.started_at) DESC
	`, deviceID)
	if err != nil {
		return nil, fmt.Errorf("query distinct subjects: %w", err)
	}
	defer rows.Close()

	var subjects []domain.UsageTotal
	for rows.Next() {
		var k, subject, label string
		var seconds int
		if err := rows.Scan(&k, &subject, &label, &seconds); err != nil {
			return nil, fmt.Errorf("scan distinct subject: %w", err)
		}
		subjects = append(subjects, domain.UsageTotal{
			Kind:    domain.Kind(k),
			Subject: subject,
			Label:   label,
			Seconds: 0,
		})
	}
	return subjects, rows.Err()
}

// DailyTotals returns counted total seconds per day for a device over a
// day range (inclusive) by aggregating per (kind, subject) and applying
// exclusion rules. This variant loads the policy (and its exclusions) from
// the store. Callers that already have the policy should use
// DailyTotalsWithExclusions to avoid the extra query.
func (s *Store) DailyTotals(deviceID int64, fromDay, toDay string) (map[string]int, error) {
	policy, err := s.GetPolicy(deviceID)
	if err != nil {
		return nil, fmt.Errorf("get policy for daily totals: %w", err)
	}
	return s.DailyTotalsWithExclusions(deviceID, fromDay, toDay, policy.Exclusions)
}

// DailyTotalsWithExclusions is like DailyTotals but accepts exclusions
// directly, avoiding an extra GetPolicy call when the caller already
// has the policy loaded.
func (s *Store) DailyTotalsWithExclusions(deviceID int64, fromDay, toDay string, exclusions []domain.Exclusion) (map[string]int, error) {
	rows, err := s.DB.Query(`
		SELECT day, kind, subject, SUM(duration_seconds) AS total_seconds
		FROM usage_events
		WHERE device_id = ? AND day >= ? AND day <= ?
		GROUP BY day, kind, subject
		ORDER BY day, kind, subject
	`, deviceID, fromDay, toDay)
	if err != nil {
		return nil, fmt.Errorf("query daily totals: %w", err)
	}
	defer rows.Close()

	// Group raw totals by day
	type rawGroup struct {
		Kind    string
		Subject string
		Seconds int
	}
	dayGroups := make(map[string][]rawGroup)
	for rows.Next() {
		var day, kind, subject string
		var seconds int
		if err := rows.Scan(&day, &kind, &subject, &seconds); err != nil {
			return nil, fmt.Errorf("scan daily total: %w", err)
		}
		dayGroups[day] = append(dayGroups[day], rawGroup{Kind: kind, Subject: subject, Seconds: seconds})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("daily totals rows: %w", err)
	}

	result := make(map[string]int)
	for day, groups := range dayGroups {
		var appTotals, webTotals []domain.UsageTotal
		for _, g := range groups {
			ut := domain.UsageTotal{
				Kind:    domain.Kind(g.Kind),
				Subject: g.Subject,
				Seconds: g.Seconds,
			}
			switch ut.Kind {
			case domain.KindApp:
				appTotals = append(appTotals, ut)
			case domain.KindWeb:
				webTotals = append(webTotals, ut)
			}
		}
		result[day] = domain.CountedTotalSeconds(appTotals, webTotals, exclusions)
	}

	return result, nil
}

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
