package store

import (
	"testing"
	"time"
)

func TestSetEmailRecipients_UnknownDeviceReturnsError(t *testing.T) {
	s := newTestStore(t)
	// Foreign keys are not enforced, so a bogus id must not silently create
	// orphan recipient rows in device_email_recipients.
	if err := s.SetEmailRecipients(999999, []string{"parent@example.com"}); err == nil {
		t.Error("expected error when setting recipients for unknown device id")
	}
}

func TestEmailRecipients_Roundtrip(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.SetEmailRecipients(dev.ID, []string{"parent@example.com", "dad@example.org"}); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}

	got, err := s.EmailRecipients(dev.ID)
	if err != nil {
		t.Fatalf("EmailRecipients: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("expected 2 recipients, got %d: %v", len(got), got)
	}
	if got[0] != "parent@example.com" {
		t.Errorf("expected first recipient 'parent@example.com', got %q", got[0])
	}
	if got[1] != "dad@example.org" {
		t.Errorf("expected second recipient 'dad@example.org', got %q", got[1])
	}
}

func TestEmailRecipients_Replace(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.SetEmailRecipients(dev.ID, []string{"old@example.com"}); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}
	if err := s.SetEmailRecipients(dev.ID, []string{"new@example.com"}); err != nil {
		t.Fatalf("SetEmailRecipients replace: %v", err)
	}

	got, err := s.EmailRecipients(dev.ID)
	if err != nil {
		t.Fatalf("EmailRecipients: %v", err)
	}
	if len(got) != 1 || got[0] != "new@example.com" {
		t.Errorf("expected only 'new@example.com', got %v", got)
	}
}

func TestEmailRecipients_DedupeAndTrim(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Duplicate (case/whitespace-insensitive) and empty entries are dropped;
	// stored lowercased.
	if err := s.SetEmailRecipients(dev.ID, []string{" Parent@Example.COM ", "parent@example.com", "", " mom@example.net "}); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}

	got, err := s.EmailRecipients(dev.ID)
	if err != nil {
		t.Fatalf("EmailRecipients: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("expected 2 unique recipients, got %d: %v", len(got), got)
	}
	if got[0] != "parent@example.com" {
		t.Errorf("expected 'parent@example.com', got %q", got[0])
	}
	if got[1] != "mom@example.net" {
		t.Errorf("expected 'mom@example.net', got %q", got[1])
	}
}

func TestEmailRecipients_Empty(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.SetEmailRecipients(dev.ID, nil); err != nil {
		t.Fatalf("SetEmailRecipients(nil): %v", err)
	}
	got, err := s.EmailRecipients(dev.ID)
	if err != nil {
		t.Fatalf("EmailRecipients: %v", err)
	}
	if len(got) != 0 {
		t.Errorf("expected no recipients, got %v", got)
	}
}

func TestTimeZone_RoundtripAndClear(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("tz-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Unset timezone is empty
	if tz, err := s.TimeZone(dev.ID); err != nil || tz != "" {
		t.Errorf("expected empty timezone before set, got %q (err %v)", tz, err)
	}

	if err := s.SetTimeZone(dev.ID, "America/New_York"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}
	if tz, err := s.TimeZone(dev.ID); err != nil || tz != "America/New_York" {
		t.Errorf("expected 'America/New_York', got %q (err %v)", tz, err)
	}

	// Empty string clears it
	if err := s.SetTimeZone(dev.ID, ""); err != nil {
		t.Fatalf("SetTimeZone clear: %v", err)
	}
	if tz, err := s.TimeZone(dev.ID); err != nil || tz != "" {
		t.Errorf("expected empty timezone after clear, got %q (err %v)", tz, err)
	}
}

func TestSetTimeZone_UnknownDeviceReturnsError(t *testing.T) {
	s := newTestStore(t)
	// Setting a timezone for a device that does not exist must fail loudly
	// rather than silently succeeding (a stale write would be dropped).
	if err := s.SetTimeZone(999999, "UTC"); err == nil {
		t.Error("expected error when setting timezone for unknown device id")
	}
}

func TestReportTargets_FiltersByRecipients(t *testing.T) {
	s := newTestStore(t)
	withRecipients, _, err := s.CreateDevice("with-recipients")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	noRecipients, _, err := s.CreateDevice("no-recipients")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.SetEmailRecipients(withRecipients.ID, []string{"parent@example.com"}); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}
	if err := s.SetTimeZone(withRecipients.ID, "Europe/Berlin"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}
	// A device with a timezone but no recipients must not appear.
	if err := s.SetTimeZone(noRecipients.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}

	targets, err := s.ReportTargets()
	if err != nil {
		t.Fatalf("ReportTargets: %v", err)
	}
	if len(targets) != 1 {
		t.Fatalf("expected 1 report target, got %d: %+v", len(targets), targets)
	}
	if targets[0].DeviceID != withRecipients.ID {
		t.Errorf("expected target device %d, got %d", withRecipients.ID, targets[0].DeviceID)
	}
	if targets[0].Name != "with-recipients" {
		t.Errorf("expected name 'with-recipients', got %q", targets[0].Name)
	}
	if targets[0].TimeZone != "Europe/Berlin" {
		t.Errorf("expected timezone 'Europe/Berlin', got %q", targets[0].TimeZone)
	}
}

func TestReportSent_MarkReportSent(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("log-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if sent, err := s.ReportSent(dev.ID, "2026-07-12"); err != nil || sent {
		t.Errorf("expected not sent before marking, got %v (err %v)", sent, err)
	}

	if err := s.MarkReportSent(dev.ID, "2026-07-12", time.Date(2026, 7, 13, 0, 10, 0, 0, time.UTC)); err != nil {
		t.Fatalf("MarkReportSent: %v", err)
	}
	if sent, err := s.ReportSent(dev.ID, "2026-07-12"); err != nil || !sent {
		t.Errorf("expected sent after marking, got %v (err %v)", sent, err)
	}

	// Idempotent: marking the same (device, day) again is ignored.
	if err := s.MarkReportSent(dev.ID, "2026-07-12", time.Now()); err != nil {
		t.Fatalf("MarkReportSent duplicate: %v", err)
	}
	var count int
	s.DB.QueryRow(`SELECT COUNT(*) FROM daily_report_log WHERE device_id = ? AND day = ?`, dev.ID, "2026-07-12").Scan(&count)
	if count != 1 {
		t.Errorf("expected exactly 1 log row, got %d", count)
	}

	// Other days / devices remain unsent.
	if sent, _ := s.ReportSent(dev.ID, "2026-07-13"); sent {
		t.Error("expected different day to remain unsent")
	}
	other, _, err := s.CreateDevice("other-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	if sent, _ := s.ReportSent(other.ID, "2026-07-12"); sent {
		t.Error("expected other device to remain unsent")
	}
}

func TestDeleteDevice_CleansRecipientsAndReportLog(t *testing.T) {
	s := newTestStore(t)
	dev, _, err := s.CreateDevice("cleanup-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.SetEmailRecipients(dev.ID, []string{"parent@example.com"}); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}
	if err := s.MarkReportSent(dev.ID, "2026-07-12", time.Now()); err != nil {
		t.Fatalf("MarkReportSent: %v", err)
	}

	if err := s.DeleteDevice(dev.ID); err != nil {
		t.Fatalf("DeleteDevice: %v", err)
	}

	var count int
	s.DB.QueryRow(`SELECT COUNT(*) FROM device_email_recipients WHERE device_id = ?`, dev.ID).Scan(&count)
	if count != 0 {
		t.Errorf("expected 0 recipients after delete, got %d", count)
	}
	s.DB.QueryRow(`SELECT COUNT(*) FROM daily_report_log WHERE device_id = ?`, dev.ID).Scan(&count)
	if count != 0 {
		t.Errorf("expected 0 report log rows after delete, got %d", count)
	}
}
