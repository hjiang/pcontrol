package report

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"pcontrol/server/internal/domain"
	"pcontrol/server/internal/store"
)

func testStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func mustAddRecipient(t *testing.T, s *store.Store, deviceID int64, emails ...string) {
	t.Helper()
	if err := s.SetEmailRecipients(deviceID, emails); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}
}

func insertUsage(t *testing.T, s *store.Store, deviceID int64, eventID, subject, label, day string, seconds int) {
	t.Helper()
	if _, err := s.DB.Exec(
		`INSERT INTO usage_events (event_id, device_id, kind, subject, label, day, started_at, duration_seconds)
		 VALUES (?, ?, 'app', ?, ?, ?, ?, ?)`,
		eventID, deviceID, subject, label, day, day+"T10:00:00Z", seconds); err != nil {
		t.Fatalf("insert usage event: %v", err)
	}
}

func TestReportDay(t *testing.T) {
	utc := time.UTC

	// 2026-07-13T00:30Z → yesterday 2026-07-12 (UTC).
	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	if got := reportDay(now, utc); got != "2026-07-12" {
		t.Errorf("reportDay(UTC) = %q, want 2026-07-12", got)
	}

	ny, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatalf("LoadLocation: %v", err)
	}

	// Same instant in NY is still 2026-07-12 local (UTC-4), so yesterday is
	// 2026-07-11 — the device's local midnight is what matters, not UTC's.
	if got := reportDay(now, ny); got != "2026-07-11" {
		t.Errorf("reportDay(NY) = %q, want 2026-07-11", got)
	}

	// DST spring-forward boundary (2026-03-08): 04:30Z is 23:30 EST on Mar 7
	// → yesterday 2026-03-06; 09:30Z is 05:30 EDT on Mar 8 → yesterday
	// 2026-03-07.
	spring := time.Date(2026, 3, 8, 4, 30, 0, 0, time.UTC)
	if got := reportDay(spring, ny); got != "2026-03-06" {
		t.Errorf("reportDay(before spring forward) = %q, want 2026-03-06", got)
	}
	springLater := time.Date(2026, 3, 8, 9, 30, 0, 0, time.UTC)
	if got := reportDay(springLater, ny); got != "2026-03-07" {
		t.Errorf("reportDay(after spring forward) = %q, want 2026-03-07", got)
	}

	// DST fall-back boundary (2026-11-01): before the transition 01:30Z is
	// 21:30 EDT on Oct 31 → yesterday 2026-10-30; after, 06:30Z is 01:30 EST
	// on Nov 1 → yesterday 2026-10-31.
	fallBack := time.Date(2026, 11, 1, 1, 30, 0, 0, time.UTC)
	if got := reportDay(fallBack, ny); got != "2026-10-30" {
		t.Errorf("reportDay(before fall back) = %q, want 2026-10-30", got)
	}
	fallBackLater := time.Date(2026, 11, 1, 6, 30, 0, 0, time.UTC)
	if got := reportDay(fallBackLater, ny); got != "2026-10-31" {
		t.Errorf("reportDay(after fall back) = %q, want 2026-10-31", got)
	}
}

func TestBuildReportBody(t *testing.T) {
	appTotals := []domain.UsageTotal{
		{Kind: domain.KindApp, Subject: "com.youtube", Label: "YouTube", Seconds: 3600},
		{Kind: domain.KindApp, Subject: "com.game", Label: "Game", Seconds: 600},
	}
	webTotals := []domain.UsageTotal{
		{Kind: domain.KindWeb, Subject: "wikipedia.org", Label: "Wikipedia", Seconds: 300},
	}
	body := buildReportBody("kid-phone", "2026-07-12", 65, appTotals, webTotals)

	for _, want := range []string{
		"Daily usage report for kid-phone",
		"Day: 2026-07-12",
		"Total counted time: 65 min",
		"Top apps:",
		"YouTube",
		"60 min",
		"Game",
		"Top websites:",
		"Wikipedia",
		"5 min",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("expected body to contain %q, got:\n%s", want, body)
		}
	}
}

func TestBuildMessage(t *testing.T) {
	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	msg := string(buildMessage("pcontrol@example.com", []string{"a@example.com", "b@example.com"}, "subject line", "body text", now))
	for _, want := range []string{
		"From: pcontrol@example.com",
		"To: a@example.com, b@example.com",
		"Subject: subject line",
		"Date: " + now.Format(time.RFC1123Z),
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=utf-8",
		"\r\n\r\nbody text",
	} {
		if !strings.Contains(msg, want) {
			t.Errorf("expected message to contain %q, got:\n%s", want, msg)
		}
	}
}

func TestBuildMessage_SanitizesHeaderInjection(t *testing.T) {
	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	// Untrusted values (device name, recipient addresses, From) must not be
	// able to inject headers: CR/LF is stripped from header fields so no new
	// header line can appear, while the body keeps its newlines.
	from := "pcontrol@example.com\r\nBcc: evil@example.com"
	to := []string{"parent@example.com", "other@example.com\r\nBcc: evil2@example.com"}
	subject := "report for kid\r\nBcc: evil3@example.com"
	body := "line one\r\nline two"
	msg := string(buildMessage(from, to, subject, body, now))

	// Split the message into the header block and the body. If header
	// injection slipped through, the header block would contain an extra
	// "Bcc:" line.
	parts := strings.SplitN(msg, "\r\n\r\n", 2)
	if len(parts) != 2 {
		t.Fatalf("expected header/body split, got:\n%s", msg)
	}
	headers := strings.Split(parts[0], "\r\n")
	if len(headers) != 6 {
		t.Errorf("expected 6 header lines, got %d:\n%s", len(headers), parts[0])
	}
	for _, line := range headers {
		if strings.HasPrefix(line, "Bcc:") {
			t.Errorf("header injection present: %q", line)
		}
	}

	// Intended recipients are preserved (CRLF stripped from the second).
	if !strings.Contains(parts[0], "To: parent@example.com, other@example.com") {
		t.Errorf("expected sanitized To header, got:\n%s", parts[0])
	}
	if !strings.Contains(parts[0], "Subject: report for kid") {
		t.Errorf("expected sanitized Subject header, got:\n%s", parts[0])
	}
	// Body content is untouched.
	if parts[1] != body {
		t.Errorf("expected body preserved with CRLF, got %q", parts[1])
	}
}

func TestSender_SendsOncePerDevicePerDay(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("kid-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	if err := s.SetTimeZone(dev.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}
	insertUsage(t, s, dev.ID, "r1", "com.youtube", "YouTube", "2026-07-12", 3600)

	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	sends := 0
	lastSubject := ""
	sender := NewSender(s, Config{Host: "smtp.example.com", Port: 587, From: "p@example.com"})
	sender.Now = func() time.Time { return now }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		sends++
		lastSubject = subject
		if len(to) != 1 || to[0] != "parent@example.com" {
			t.Errorf("unexpected recipients: %v", to)
		}
		if !strings.Contains(body, "Total counted time: 60 min") {
			t.Errorf("expected counted total in body, got:\n%s", body)
		}
		return nil
	}

	sender.RunOnce()
	if sends != 1 {
		t.Fatalf("expected 1 send on first run, got %d", sends)
	}
	if !strings.Contains(lastSubject, "2026-07-12") {
		t.Errorf("expected subject to reference 2026-07-12, got %q", lastSubject)
	}

	// A later tick on the same local day must not re-send.
	now = time.Date(2026, 7, 13, 12, 0, 0, 0, time.UTC)
	sender.RunOnce()
	if sends != 1 {
		t.Errorf("expected still 1 send after same-day tick, got %d", sends)
	}
}

func TestSender_NoResendAfterRestart(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("kid-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	if err := s.SetTimeZone(dev.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}
	insertUsage(t, s, dev.ID, "r1", "com.youtube", "YouTube", "2026-07-12", 600)

	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	sends := 0
	newSender := func() *Sender {
		snd := NewSender(s, Config{Host: "smtp.example.com", Port: 587, From: "p@example.com"})
		snd.Now = func() time.Time { return now }
		snd.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
			sends++
			return nil
		}
		return snd
	}

	newSender().RunOnce()
	if sends != 1 {
		t.Fatalf("expected 1 send on first run, got %d", sends)
	}

	// A fresh Sender over the same store (simulating a server restart) must
	// not re-send, because the send log row is durable.
	newSender().RunOnce()
	if sends != 1 {
		t.Errorf("expected no resend after restart, got %d sends", sends)
	}
}

func TestSender_FailedSendIsRetried(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("kid-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	if err := s.SetTimeZone(dev.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}
	insertUsage(t, s, dev.ID, "r1", "com.youtube", "YouTube", "2026-07-12", 600)

	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	attempts := 0
	sender := NewSender(s, Config{Host: "smtp.example.com", Port: 587})
	sender.Now = func() time.Time { return now }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		attempts++
		return fmt.Errorf("smtp down")
	}

	sender.RunOnce()
	if attempts != 1 {
		t.Fatalf("expected 1 send attempt, got %d", attempts)
	}
	if sent, _ := s.ReportSent(dev.ID, "2026-07-12"); sent {
		t.Error("expected report NOT marked sent after failed send")
	}

	// Next tick retries and succeeds.
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		attempts++
		return nil
	}
	sender.RunOnce()
	if attempts != 2 {
		t.Errorf("expected a retry attempt, got %d total", attempts)
	}
	if sent, _ := s.ReportSent(dev.ID, "2026-07-12"); !sent {
		t.Error("expected report marked sent after successful send")
	}
}

func TestSender_SkipsDeviceWithoutRecipients(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("no-recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	// Timezone set but no recipients — must not appear as a target.
	if err := s.SetTimeZone(dev.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}

	sends := 0
	sender := NewSender(s, Config{Host: "smtp.example.com", Port: 587})
	sender.Now = func() time.Time { return time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC) }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		sends++
		return nil
	}

	sender.RunOnce()
	if sends != 0 {
		t.Errorf("expected 0 sends for device without recipients, got %d", sends)
	}
}

func TestSender_SkipsWhenSMTPNotConfigured(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	if err := s.SetTimeZone(dev.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}

	sends := 0
	sender := NewSender(s, Config{}) // Host == ""
	sender.Now = func() time.Time { return time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC) }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		sends++
		return nil
	}

	sender.RunOnce()
	if sends != 0 {
		t.Errorf("expected 0 sends when SMTP not configured, got %d", sends)
	}
}

func TestSender_InvalidTimeZoneFallsBackToUTC(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	// The store does not validate timezones (the web layer does); an invalid
	// value must fall back to UTC rather than panic.
	if err := s.SetTimeZone(dev.ID, "Not/AZone"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}

	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	sends := 0
	lastSubject := ""
	sender := NewSender(s, Config{Host: "smtp.example.com", Port: 587})
	sender.Now = func() time.Time { return now }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		sends++
		lastSubject = subject
		return nil
	}

	sender.RunOnce()
	if sends != 1 {
		t.Fatalf("expected 1 send with UTC fallback, got %d", sends)
	}
	if !strings.Contains(lastSubject, "2026-07-12") {
		t.Errorf("expected UTC day 2026-07-12 in subject, got %q", lastSubject)
	}
}

func TestSender_UnsetTimeZoneDefaultsToUTCWithoutWarning(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	// No SetTimeZone call: the device uses the default UTC behavior ("").

	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	sends := 0
	var logBuf bytes.Buffer
	sender := NewSender(s, Config{Host: "smtp.example.com", Port: 587})
	sender.Log = log.New(&logBuf, "", 0)
	sender.Now = func() time.Time { return now }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		sends++
		return nil
	}

	sender.RunOnce()

	if sends != 1 {
		t.Fatalf("expected 1 send for unset timezone (UTC default), got %d", sends)
	}
	if strings.Contains(logBuf.String(), "invalid timezone") {
		t.Errorf("unexpected invalid-timezone warning for empty timezone: %q", logBuf.String())
	}
}

func TestSender_MarkReportSentRetried(t *testing.T) {
	s := testStore(t)
	dev, _, err := s.CreateDevice("kid-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	mustAddRecipient(t, s, dev.ID, "parent@example.com")
	if err := s.SetTimeZone(dev.ID, "UTC"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}
	insertUsage(t, s, dev.ID, "r1", "com.youtube", "YouTube", "2026-07-12", 600)

	now := time.Date(2026, 7, 13, 0, 30, 0, 0, time.UTC)
	sends := 0
	markCalls := 0
	sender := NewSender(s, Config{Host: "smtp.example.com", Port: 587})
	sender.Now = func() time.Time { return now }
	sender.SendFunc = func(cfg Config, from string, to []string, subject, body string) error {
		sends++
		return nil
	}
	// Simulate a transient DB failure on the first two mark attempts: the
	// send must happen exactly once, the mark must be retried, and the
	// report must end up recorded as sent.
	sender.MarkFunc = func(deviceID int64, day string, t time.Time) error {
		markCalls++
		if markCalls <= 2 {
			return fmt.Errorf("transient db error")
		}
		return s.MarkReportSent(deviceID, day, t)
	}

	sender.RunOnce()
	if sends != 1 {
		t.Fatalf("expected exactly 1 send, got %d", sends)
	}
	if markCalls < 3 {
		t.Errorf("expected mark retried past the transient failures, got %d calls", markCalls)
	}
	if sent, _ := s.ReportSent(dev.ID, "2026-07-12"); !sent {
		t.Error("expected report marked sent after retried mark")
	}
}

func TestSendEmail_TimesOutOnHungServer(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	// A server that accepts the connection but never responds.
	accepted := make(chan struct{})
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		close(accepted)
		// Hold the connection open without sending a greeting.
		io.Copy(io.Discard, conn)
	}()

	oldDial, oldExchange := smtpDialTimeout, smtpExchangeTimeout
	smtpDialTimeout = 100 * time.Millisecond
	smtpExchangeTimeout = 500 * time.Millisecond
	t.Cleanup(func() {
		smtpDialTimeout, smtpExchangeTimeout = oldDial, oldExchange
	})

	host, portStr, err := net.SplitHostPort(ln.Addr().String())
	if err != nil {
		t.Fatalf("split addr: %v", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("parse port: %v", err)
	}

	cfg := Config{Host: host, Port: port}
	start := time.Now()
	err = sendEmail(cfg, "from@example.com", []string{"to@example.com"}, "subject", "body")
	if err == nil {
		t.Fatal("expected an error from a hung SMTP server")
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("sendEmail took %v, expected it to time out quickly", elapsed)
	}
	<-accepted
}
