package report

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"mime"
	"net"
	"net/smtp"
	"strings"
	"time"

	"pcontrol/server/internal/domain"
	"pcontrol/server/internal/store"
)

// Config holds settings for sending daily usage reports.
type Config struct {
	Host     string
	Port     int
	Username string
	Password string
	From     string

	// SendAfter is how long after local midnight to wait before sending the
	// previous day's report (default 3h). It lets late client-side usage
	// ingestion land before the report is compiled. An unset (0) value means
	// "use the default".
	SendAfter time.Duration
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

	// MarkFunc records a successful send in the durable send log; defaults
	// to Store.MarkReportSent. Override in tests to simulate transient DB
	// failures without touching a real database.
	MarkFunc func(deviceID int64, day string, t time.Time) error

	// SendAfter is the minimum local time-of-day (measured from midnight) at
	// or after which the previous day's report may be sent. Derived from
	// Config.SendAfter, defaulting to 3h when unset.
	SendAfter time.Duration

	tickInterval time.Duration
	warned       map[int64]bool // devices whose timezone failed to load (log once)

	// NOTE: warned is accessed only from the single ticker goroutine (or a
	// sequential test) and is not safe for concurrent use; do not read or
	// write it from other goroutines.
}

// NewSender returns a Sender with sensible defaults.
func NewSender(st *store.Store, cfg Config) *Sender {
	s := &Sender{
		Store:     st,
		Cfg:       cfg,
		Log:       log.Default(),
		Now:       time.Now,
		SendAfter: cfg.SendAfter,
		warned:    make(map[int64]bool),
	}
	if s.SendAfter == 0 {
		s.SendAfter = 3 * time.Hour
	}
	return s
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
	// An unset timezone means UTC (see domain.ReportTarget.TimeZone); load it
	// explicitly rather than relying on time.LoadLocation("") returning UTC.
	loc := time.UTC
	if t.TimeZone != "" {
		var err error
		if loc, err = time.LoadLocation(t.TimeZone); err != nil {
			if !s.warned[t.DeviceID] {
				s.logf("report: device %d: invalid timezone %q, using UTC: %v", t.DeviceID, t.TimeZone, err)
				s.warned[t.DeviceID] = true
			}
			loc = time.UTC
		}
	}
	day := reportDay(now, loc)

	// Delay sending until SendAfter past local midnight: usage reporting can
	// lag on the client (device offline, retried syncs), so emailing at
	// midnight may capture an incomplete previous day. Default 3h.
	if !s.dueForLocalTime(now, loc) {
		return
	}

	sent, err := s.Store.ReportSent(t.DeviceID, day)
	if err != nil {
		s.logf("report: device %d: check sent: %v", t.DeviceID, err)
		return
	}
	if sent {
		return
	}

	delivered, err := s.sendReport(t, day)
	if err != nil {
		s.logf("report: device %d (%s): send: %v", t.DeviceID, day, err)
		return
	}
	if !delivered {
		// No email was sent (recipients cleared after targets were listed); do
		// not record the day in the send log or a later tick would see it as
		// already sent and permanently suppress this day's report.
		return
	}
	if err := s.markSent(t.DeviceID, day, now); err != nil {
		s.logf("report: device %d: mark sent %s: %v", t.DeviceID, day, err)
	}
}

// markSent records a successful send in the durable send log, retrying on
// transient DB errors. The report was already emailed, so we must not leave
// the log unwritten: a missing row would make the next tick re-send the same
// report, violating the at-most-once-per-device/day guarantee. Retrying here
// (instead of resending) closes that gap; a permanent failure is logged and
// the next tick will retry the whole send, which is acceptable because the
// send log row is only missing when the DB was down for the mark too.
func (s *Sender) markSent(deviceID int64, day string, now time.Time) error {
	mark := s.MarkFunc
	if mark == nil {
		mark = s.Store.MarkReportSent
	}
	const attempts = 5
	const delay = 200 * time.Millisecond
	var err error
	for i := 0; i < attempts; i++ {
		if err = mark(deviceID, day, now); err == nil {
			return nil
		}
		if i < attempts-1 {
			time.Sleep(delay)
		}
	}
	return err
}

func (s *Sender) sendReport(t domain.ReportTarget, day string) (bool, error) {
	recipients, err := s.Store.EmailRecipients(t.DeviceID)
	if err != nil {
		return false, fmt.Errorf("list recipients: %w", err)
	}
	if len(recipients) == 0 {
		// Recipients were cleared between listing targets and sending. Without
		// a non-nil error, sendIfDue would treat this as a successful send and
		// record the day as sent, permanently suppressing a report that was
		// never emailed. Report (false, nil) so the caller skips the mark.
		return false, nil
	}

	appTotals, webTotals, err := s.Store.UsageTotals(t.DeviceID, day)
	if err != nil {
		return false, fmt.Errorf("usage totals: %w", err)
	}
	policy, err := s.Store.GetPolicy(t.DeviceID)
	if err != nil {
		return false, fmt.Errorf("get policy: %w", err)
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
	if err := send(s.Cfg, from, recipients, subject, body); err != nil {
		return false, err
	}
	return true, nil
}

func (s *Sender) logf(format string, args ...interface{}) {
	if s.Log != nil {
		s.Log.Printf(format, args...)
		return
	}
	log.Printf(format, args...)
}

// dueForLocalTime reports whether now is at/after SendAfter past local
// midnight in loc. The boundary is (local midnight of today) + SendAfter in
// absolute time, so a DST transition cannot shift it. A non-positive
// SendAfter (a value set directly on the Sender, bypassing NewSender's
// default) means send as soon as the day rolls over.
func (s *Sender) dueForLocalTime(now time.Time, loc *time.Location) bool {
	if s.SendAfter <= 0 {
		return true
	}
	local := now.In(loc)
	y, m, d := local.Date()
	midnight := time.Date(y, m, d, 0, 0, 0, 0, loc)
	return !now.Before(midnight.Add(s.SendAfter))
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
		cleanTo[i] = encodeHeader(addr)
	}
	return []byte("From: " + encodeHeader(from) + "\r\n" +
		"To: " + strings.Join(cleanTo, ", ") + "\r\n" +
		"Subject: " + encodeHeader(subject) + "\r\n" +
		"Date: " + now.Format(time.RFC1123Z) + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=utf-8\r\n" +
		"\r\n" +
		body)
}

// sanitizeHeader removes CR, LF and other control characters from a header
// value so a value cannot inject additional headers or break the wire format.
// It is used for SMTP envelope values (From/To addresses must stay plain).
func sanitizeHeader(s string) string {
	return strings.Map(func(r rune) rune {
		if r == '\r' || r == '\n' || r == 0x7f || (r < 0x20 && r != '\t') {
			return -1
		}
		return r
	}, s)
}

// encodeHeader sanitizes a header value and, if it contains non-ASCII bytes
// (e.g. a device name with Unicode), RFC 2047-encodes it so the emitted
// header stays RFC 5322-compliant and is not rejected or mangled by MTAs.
func encodeHeader(s string) string {
	s = sanitizeHeader(s)
	for i := 0; i < len(s); i++ {
		if s[i] >= 0x80 {
			return mime.QEncoding.Encode("utf-8", s)
		}
	}
	return s
}

// sendEmail delivers an email over SMTP with explicit timeouts so a hung or
// unresponsive server cannot stall the report scheduler indefinitely: the
// dial is bounded by smtpDialTimeout and the whole exchange (connect through
// QUIT) by smtpExchangeTimeout. STARTTLS is used opportunistically when the
// server advertises it (standard for port 587); implicit-TLS ports (465) are
// not supported. AUTH is attempted only when a username is configured.
func sendEmail(cfg Config, from string, to []string, subject, body string) error {
	msg := buildMessage(from, to, subject, body, time.Now())
	addr := cfg.Addr()

	// Header values are sanitized for the message above; sanitize again for
	// the SMTP envelope so CR/LF cannot inject MAIL/RCPT commands.
	from = sanitizeHeader(from)
	cleanTo := make([]string, len(to))
	for i, addr := range to {
		cleanTo[i] = sanitizeHeader(addr)
	}

	dialer := &net.Dialer{Timeout: smtpDialTimeout}
	conn, err := dialer.Dial("tcp", addr)
	if err != nil {
		return fmt.Errorf("smtp dial %s: %w", addr, err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(smtpExchangeTimeout)); err != nil {
		return fmt.Errorf("smtp set deadline: %w", err)
	}

	c, err := smtp.NewClient(conn, cfg.Host)
	if err != nil {
		return fmt.Errorf("smtp connect: %w", err)
	}
	defer c.Close()

	if ok, _ := c.Extension("STARTTLS"); ok {
		if err := c.StartTLS(&tls.Config{ServerName: cfg.Host}); err != nil {
			return fmt.Errorf("smtp starttls: %w", err)
		}
	}

	if cfg.Username != "" {
		auth := smtp.PlainAuth("", cfg.Username, cfg.Password, cfg.Host)
		if err := c.Auth(auth); err != nil {
			return fmt.Errorf("smtp auth: %w", err)
		}
	}

	if err := c.Mail(from); err != nil {
		return fmt.Errorf("smtp mail: %w", err)
	}
	for _, rcpt := range cleanTo {
		if err := c.Rcpt(rcpt); err != nil {
			return fmt.Errorf("smtp rcpt %s: %w", rcpt, err)
		}
	}

	w, err := c.Data()
	if err != nil {
		return fmt.Errorf("smtp data: %w", err)
	}
	if _, err := w.Write(msg); err != nil {
		return fmt.Errorf("smtp write: %w", err)
	}
	if err := w.Close(); err != nil {
		return fmt.Errorf("smtp write close: %w", err)
	}
	return c.Quit()
}

var (
	// smtpDialTimeout bounds establishing the SMTP connection.
	smtpDialTimeout = 30 * time.Second
	// smtpExchangeTimeout bounds the whole SMTP exchange (dial through
	// QUIT) so a hung server cannot stall the report scheduler forever.
	smtpExchangeTimeout = 60 * time.Second
)
