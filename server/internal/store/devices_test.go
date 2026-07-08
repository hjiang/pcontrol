package store

import (
	"testing"
	"time"

	"pcontrol/server/internal/domain"
)

func TestCreateDevice(t *testing.T) {
	s := newTestStore(t)

	dev, rawToken, err := s.CreateDevice("test-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	if dev.Name != "test-phone" {
		t.Errorf("expected name 'test-phone', got %q", dev.Name)
	}
	if dev.ID == 0 {
		t.Error("expected non-zero device ID")
	}
	if len(rawToken) < 20 {
		t.Errorf("expected raw token of at least 20 chars, got %d", len(rawToken))
	}
	if dev.TokenHash == "" {
		t.Error("expected non-empty token hash")
	}
}

func TestDeviceByToken(t *testing.T) {
	s := newTestStore(t)

	dev, rawToken, err := s.CreateDevice("test-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	got, err := s.DeviceByToken(rawToken)
	if err != nil {
		t.Fatalf("DeviceByToken: %v", err)
	}
	if got.ID != dev.ID {
		t.Errorf("expected device ID %d, got %d", dev.ID, got.ID)
	}
	if got.Name != "test-phone" {
		t.Errorf("expected name 'test-phone', got %q", got.Name)
	}
}

func TestDeviceByToken_Unknown(t *testing.T) {
	s := newTestStore(t)

	_, err := s.DeviceByToken("this-token-does-not-exist")
	if err == nil {
		t.Fatal("expected error for unknown token")
	}
}

func TestTouchLastSeen(t *testing.T) {
	s := newTestStore(t)

	dev, _, err := s.CreateDevice("test-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.TouchLastSeen(dev.ID, time.Now()); err != nil {
		t.Fatalf("TouchLastSeen: %v", err)
	}

	got, err := s.DeviceByTokenFromID(dev.ID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}
	if got.LastSeenAt == nil {
		t.Fatal("expected last_seen_at to be set after TouchLastSeen")
	}
}

func TestDevice_BatteryFieldsNilAfterCreate(t *testing.T) {
	s := newTestStore(t)

	dev, _, err := s.CreateDevice("test-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	got, err := s.DeviceByTokenFromID(dev.ID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}

	if got.BatteryPercent != nil {
		t.Error("expected nil BatteryPercent after create")
	}
	if got.BatteryCharging != nil {
		t.Error("expected nil BatteryCharging after create")
	}
	if got.BatteryUpdatedAt != nil {
		t.Error("expected nil BatteryUpdatedAt after create")
	}
}

func TestRenameDevice(t *testing.T) {
	s := newTestStore(t)

	dev, _, err := s.CreateDevice("old-name")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.RenameDevice(dev.ID, "new-name"); err != nil {
		t.Fatalf("RenameDevice: %v", err)
	}

	got, err := s.DeviceByTokenFromID(dev.ID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}
	if got.Name != "new-name" {
		t.Errorf("expected name 'new-name', got %q", got.Name)
	}
}

func TestRenameDevice_EmptyNameRejected(t *testing.T) {
	s := newTestStore(t)

	dev, _, err := s.CreateDevice("some-name")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.RenameDevice(dev.ID, ""); err == nil {
		t.Error("expected error for empty name, got nil")
	}
}

func TestDeleteDevice(t *testing.T) {
	s := newTestStore(t)

	dev, _, err := s.CreateDevice("delete-me")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Add some events, a limit, an exclusion, and settings
	_, err = s.DB.Exec(`INSERT INTO usage_events (event_id, device_id, kind, subject, label, day, started_at, duration_seconds)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		"e1", dev.ID, "app", "com.test", "Test", "2026-07-06", "2026-07-06T00:00:00Z", 120)
	if err != nil {
		t.Fatalf("insert event: %v", err)
	}
	if _, err := s.SetLimit(dev.ID, "app", "com.test", 60); err != nil {
		t.Fatalf("SetLimit: %v", err)
	}
	if _, err := s.AddExclusion(dev.ID, "app", "com.test"); err != nil {
		t.Fatalf("AddExclusion: %v", err)
	}
	tmin := 120
	if err := s.SetTotalLimit(dev.ID, &tmin); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}

	// Delete the device
	if err := s.DeleteDevice(dev.ID); err != nil {
		t.Fatalf("DeleteDevice: %v", err)
	}

	// Device should be gone
	if _, err := s.DeviceByTokenFromID(dev.ID); err == nil {
		t.Error("expected error fetching deleted device")
	}

	// Child rows should be gone
	var count int
	s.DB.QueryRow(`SELECT COUNT(*) FROM usage_events WHERE device_id = ?`, dev.ID).Scan(&count)
	if count != 0 {
		t.Errorf("expected 0 usage_events after delete, got %d", count)
	}

	s.DB.QueryRow(`SELECT COUNT(*) FROM limits WHERE device_id = ?`, dev.ID).Scan(&count)
	if count != 0 {
		t.Errorf("expected 0 limits after delete, got %d", count)
	}

	s.DB.QueryRow(`SELECT COUNT(*) FROM exclusions WHERE device_id = ?`, dev.ID).Scan(&count)
	if count != 0 {
		t.Errorf("expected 0 exclusions after delete, got %d", count)
	}

	s.DB.QueryRow(`SELECT COUNT(*) FROM device_settings WHERE device_id = ?`, dev.ID).Scan(&count)
	if count != 0 {
		t.Errorf("expected 0 device_settings after delete, got %d", count)
	}
}

func TestUpdateBatteryStatus(t *testing.T) {
	s := newTestStore(t)

	dev, _, err := s.CreateDevice("test-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	now := time.Date(2026, 7, 6, 12, 30, 0, 0, time.UTC)
	if err := s.UpdateBatteryStatus(dev.ID, 55, true, now); err != nil {
		t.Fatalf("UpdateBatteryStatus: %v", err)
	}

	got, err := s.DeviceByTokenFromID(dev.ID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}

	if got.BatteryPercent == nil || *got.BatteryPercent != 55 {
		t.Errorf("expected BatteryPercent 55, got %v", got.BatteryPercent)
	}
	if got.BatteryCharging == nil || *got.BatteryCharging != true {
		t.Errorf("expected BatteryCharging true, got %v", got.BatteryCharging)
	}
	if got.BatteryUpdatedAt == nil || !got.BatteryUpdatedAt.Equal(now) {
		t.Errorf("expected BatteryUpdatedAt %v, got %v", now, got.BatteryUpdatedAt)
	}
}

// --- helpers ---

func newTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatalf("Open test store: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func mustCreateDevice(t *testing.T, s *Store, name string) (domain.Device, string) {
	t.Helper()
	dev, raw, err := s.CreateDevice(name)
	if err != nil {
		t.Fatalf("CreateDevice(%q): %v", name, err)
	}
	return dev, raw
}
