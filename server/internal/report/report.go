package report

import (
	"context"
	"fmt"
	"log"
	"net/smtp"
	"strings"
	"time"

	"pcontrol/server/internal/domain"
	"pcontrol/server/internal/store"
)

// Config holds SMTP settings for sending daily usage reports.
type Config struct {
	Host     string
	Port     int
	Username string
	Password string
	From     string
}

// Addr returns the SMTP dial address in "host:port" form, defaulting the
// port to 587 when unset.
func (c Config) Addr() string {
	if c.Port == 0 {
		c.Port = 587
	}
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}

// Sender emails daily usage reports for devices that have recipients
// configured. It is testable: Now and SendFunc are injectable, and RunOnce
// performs a single check pass so tests never need to wait on the ticker.
type Sender struct {
	Store *store.Store
	Cfg   Config
	Log   *log.Logger

	// Now returns the current time; defaults to time.Now. Override in tests.
	Now func() time.Time

	// SendFunc is the email transport; defaults to sendEmail. Override in
	// tests to avoid real network I/O.
	SendFunc func(cfg Config, from string, to []string, subject, body string) error

	tickInterval time.Duration
	warned       map[int64]bool // devices whose timezone failed to load (log once)

	// NOTE: warned is accessed only from the single ticker goroutine (or a
	// sequential test) and is not safe for concurrent use; do not read or
	// write it from other goroutines.
}

// NewSender returns a Sender with sensible defaults.
func NewSender(st *store.Store, cfg Config) *Sender {
	return &Sender{
		Store:  st,
		Cfg:    cfg,
		Log:    log.Default(),
		Now:    time.Now,
		warned: make(map[int64]bool),
	}
}

// Run loops forever, running a check pass every minute until ctx is done.
func (s *Sender) Run(ctx context.Context) {
	interval := s.tickInterval
	if interval <= 0 {
		interval = time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.RunOnce()
		}
	}
}

// RunOnce checks every report target and sends the previous day's report when
// it has not been sent yet. Safe to call repeatedly and cheap when nothing is
// due.
func (s *Sender) RunOnce() {
	if s.Cfg.Host == "" {
		return // SMTP not configured; the job is normally not started at all.
	}
	targets, err := s.Store.ReportTargets()
	if err != nil {
		s.logf("report: list targets: %v", err)
		return
	}
	now := s.Now()
	for _, t := range targets {
		s.sendIfDue(t, now)
	}
}

func (s *Sender) sendIfDue(t domain.ReportTarget, now time.Time) {
	loc, err := time.LoadLocation(t.TimeZone)
	if err != nil {
		if !s.warned[t.DeviceID] {
			s.logf("report: device %d: invalid timezone %q, using UTC: %v", t.DeviceID, t.TimeZone, err)
			s.warned[t.DeviceID] = true
		}
		loc = time.UTC
	}
	day := reportDay(now, loc)

	sent, err := s.Store.ReportSent(t.DeviceID, day)
	if err != nil {
		s.logf("report: device %d: check sent: %v", t.DeviceID, err)
		return
	}
	if sent {
		return
	}

	if err := s.sendReport(t, day); err != nil {
		s.logf("report: device %d (%s): send: %v", t.DeviceID, day, err)
		return
	}
	if err := s.Store.MarkReportSent(t.DeviceID, day, now); err != nil {
		s.logf("report: device %d: mark sent %s: %v", t.DeviceID, day, err)
	}
}

func (s *Sender) sendReport(t domain.ReportTarget, day string) error {
	recipients, err := s.Store.EmailRecipients(t.DeviceID)
	if err != nil {
		return fmt.Errorf("list recipients: %w", err)
	}
	if len(recipients) == 0 {
		return nil // recipients were cleared between listing targets and sending
	}

	appTotals, webTotals, err := s.Store.UsageTotals(t.DeviceID, day)
	if err != nil {
		return fmt.Errorf("usage totals: %w", err)
	}
	policy, err := s.Store.GetPolicy(t.DeviceID)
	if err != nil {
		return fmt.Errorf("get policy: %w", err)
	}

	// A day with no tracked usage still gets a report ("Total counted time:
	// 0 min") — the parent receives a daily report even when the device had
	// no usage, which is itself useful signal (e.g. the device was off or
	// tracking failed).
	totalMinutes := domain.CountedTotalSeconds(appTotals, webTotals, policy.Exclusions) / 60
	body := buildReportBody(t.Name, day, totalMinutes, appTotals, webTotals)

	from := s.Cfg.From
	if from == "" {
		from = "pcontrol@localhost"
	}
	subject := fmt.Sprintf("pcontrol usage report for %s (%s)", t.Name, day)

	send := s.SendFunc
	if send == nil {
		send = sendEmail
	}
	return send(s.Cfg, from, recipients, subject, body)
}

func (s *Sender) logf(format string, args ...interface{}) {
	if s.Log != nil {
		s.Log.Printf(format, args...)
		return
	}
	log.Printf(format, args...)
}

// reportDay returns the "YYYY-MM-DD" day key for the daily report: yesterday
// in the given location.
func reportDay(now time.Time, loc *time.Location) string {
	return now.In(loc).AddDate(0, 0, -1).Format("2006-01-02")
}

// buildReportBody renders the plain-text report body.
func buildReportBody(deviceName, day string, countedMinutes int, appTotals, webTotals []domain.UsageTotal) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Daily usage report for %s\nDay: %s\n\n", deviceName, day)
	fmt.Fprintf(&b, "Total counted time: %d min\n\n", countedMinutes)

	if len(appTotals) > 0 {
		b.WriteString("Top apps:\n")
		for _, a := range appTotals[:min(10, len(appTotals))] {
			label := a.Label
			if label == "" {
				label = a.Subject
			}
			fmt.Fprintf(&b, "  %-28s %5d min\n", label, a.Seconds/60)
		}
		b.WriteString("\n")
	}

	if len(webTotals) > 0 {
		b.WriteString("Top websites:\n")
		for _, w := range webTotals[:min(10, len(webTotals))] {
			label := w.Label
			if label == "" {
				label = w.Subject
			}
			fmt.Fprintf(&b, "  %-28s %5d min\n", label, w.Seconds/60)
		}
	}

	return strings.TrimRight(b.String(), "\n") + "\n"
}

// buildMessage assembles a plain-text RFC 5322 message with the required
// headers. Header fields are sanitized of CR/LF because untrusted values
// (device names, recipient addresses, From) flow into them; the body is left
// untouched.
func buildMessage(from string, to []string, subject, body string, now time.Time) []byte {
	cleanTo := make([]string, len(to))
	for i, addr := range to {
		cleanTo[i] = sanitizeHeader(addr)
	}
	return []byte("From: " + sanitizeHeader(from) + "\r\n" +
		"To: " + strings.Join(cleanTo, ", ") + "\r\n" +
		"Subject: " + sanitizeHeader(subject) + "\r\n" +
		"Date: " + now.Format(time.RFC1123Z) + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=utf-8\r\n" +
		"\r\n" +
		body)
}

// sanitizeHeader removes CR and LF from a header value so a value cannot
// inject additional headers into the message.
func sanitizeHeader(s string) string {
	s = strings.ReplaceAll(s, "\r", "")
	s = strings.ReplaceAll(s, "\n", "")
	return s
}

// sendEmail delivers an email over SMTP using net/smtp. STARTTLS is used
// opportunistically when the server advertises it (standard for port 587);
// implicit-TLS ports (465) are not supported. AUTH is attempted only when a
// username is configured.
func sendEmail(cfg Config, from string, to []string, subject, body string) error {
	var auth smtp.Auth
	if cfg.Username != "" {
		auth = smtp.PlainAuth("", cfg.Username, cfg.Password, cfg.Host)
	}
	return smtp.SendMail(cfg.Addr(), auth, from, to, buildMessage(from, to, subject, body, time.Now()))
}
