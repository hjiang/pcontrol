package store

import (
	"testing"
	"time"

	"pcontrol/server/internal/domain"
)

func TestInsertEvents(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	events := []domain.Event{
		{EventID: "uuid-1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 120},
		{EventID: "uuid-2", DeviceID: dev.ID, Kind: domain.KindWeb, Subject: "youtube.com", Label: "YouTube", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 60},
	}

	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}
}

func TestInsertEvents_DuplicateIgnored(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	events := []domain.Event{
		{EventID: "uuid-1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 120},
	}

	// Insert same event twice
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("first insert: %v", err)
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("second insert (should be no-op): %v", err)
	}

	// Should have exactly 1 row
	apps, web, err := s.UsageTotals(dev.ID, "2026-07-03")
	if err != nil {
		t.Fatalf("UsageTotals: %v", err)
	}
	var totalRows int
	for _, a := range apps {
		totalRows += a.Seconds / 120
	}
	for _, w := range web {
		totalRows += w.Seconds / 60
	}
	if totalRows != 1 {
		t.Errorf("expected 1 unique event row, got %d rows from aggregates", totalRows)
	}
}

func TestUsageTotals(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	events := []domain.Event{
		{EventID: "u1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 120},
		{EventID: "u2", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 60},
		{EventID: "u3", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.other", Label: "Other", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 30},
		{EventID: "u4", DeviceID: dev.ID, Kind: domain.KindWeb, Subject: "youtube.com", Label: "YouTube", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 300},
		{EventID: "u5", DeviceID: dev.ID, Kind: domain.KindWeb, Subject: "youtube.com", Label: "YouTube", Day: "2026-07-04", StartedAt: time.Now(), DurationSeconds: 100},
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}

	apps, web, err := s.UsageTotals(dev.ID, "2026-07-03")
	if err != nil {
		t.Fatalf("UsageTotals: %v", err)
	}

	// Verify app totals
	appMap := make(map[string]int)
	for _, a := range apps {
		appMap[a.Subject] = a.Seconds
	}
	if appMap["com.game"] != 180 {
		t.Errorf("expected com.game=180, got %d", appMap["com.game"])
	}
	if appMap["com.other"] != 30 {
		t.Errorf("expected com.other=30, got %d", appMap["com.other"])
	}

	// Verify web totals
	if len(web) != 1 {
		t.Fatalf("expected 1 web total, got %d", len(web))
	}
	if web[0].Subject != "youtube.com" || web[0].Seconds != 300 {
		t.Errorf("expected youtube.com=300, got %s=%d", web[0].Subject, web[0].Seconds)
	}
}

func TestDailyTotals(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	// Add an exclusion for "khanacademy.org" web usage
	s.AddExclusion(dev.ID, "web", "khanacademy.org")

	events := []domain.Event{
		// Day 1: 120s app, 60s excluded web
		{EventID: "d1e1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 120},
		{EventID: "d1e2", DeviceID: dev.ID, Kind: domain.KindWeb, Subject: "khanacademy.org", Label: "Khan", Day: "2026-07-03", StartedAt: time.Now(), DurationSeconds: 60},
		// Day 2: 30s app, 200s non-excluded web
		{EventID: "d2e1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: "2026-07-04", StartedAt: time.Now(), DurationSeconds: 30},
		{EventID: "d2e2", DeviceID: dev.ID, Kind: domain.KindWeb, Subject: "youtube.com", Label: "YouTube", Day: "2026-07-04", StartedAt: time.Now(), DurationSeconds: 200},
		// Day 3: 90s app (excluded app does not subtract)
		{EventID: "d3e1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.other", Label: "Other", Day: "2026-07-05", StartedAt: time.Now(), DurationSeconds: 90},
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}

	totals, err := s.DailyTotals(dev.ID, "2026-07-03", "2026-07-05")
	if err != nil {
		t.Fatalf("DailyTotals: %v", err)
	}

	// Day 1: 120s app - 60s excluded web = 60s = 1 min
	if totals["2026-07-03"] != 60 {
		t.Errorf("expected day 2026-07-03 total 60, got %d", totals["2026-07-03"])
	}

	// Day 2: 30s app only (web is non-excluded, already in browser app time)
	if totals["2026-07-04"] != 30 {
		t.Errorf("expected day 2026-07-04 total 30, got %d", totals["2026-07-04"])
	}

	// Day 3: 90s app (no exclusions match)
	if totals["2026-07-05"] != 90 {
		t.Errorf("expected day 2026-07-05 total 90, got %d", totals["2026-07-05"])
	}
}

func TestUsageTotals_EmptyDay(t *testing.T) {
	s := newTestStore(t)
	dev, _ := mustCreateDevice(t, s, "phone-1")

	apps, web, err := s.UsageTotals(dev.ID, "2099-01-01")
	if err != nil {
		t.Fatalf("UsageTotals: %v", err)
	}
	if len(apps) != 0 {
		t.Errorf("expected 0 app totals, got %d", len(apps))
	}
	if len(web) != 0 {
		t.Errorf("expected 0 web totals, got %d", len(web))
	}
}
