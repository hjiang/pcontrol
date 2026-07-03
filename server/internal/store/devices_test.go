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
