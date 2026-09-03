package web

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"pcontrol/server/internal/domain"
)

func TestDashboard_NoDevices(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	// Login first
	sessionCookie := loginSession(t, mux)

	// Request dashboard
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "No devices registered yet") {
		t.Error("expected 'No devices registered yet' in dashboard body")
	}
	if !strings.Contains(body, "Register device") {
		t.Error("expected 'Register device' link in dashboard body")
	}
}

func TestRegisterDevice_SuccessScreen(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	body := "name=gaming-pc"
	req := httptest.NewRequest(http.MethodPost, "/devices/new", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 after device registration, got %d", rec.Code)
	}

	resp := rec.Body.String()

	// Success heading
	if !strings.Contains(resp, "Device Registered") {
		t.Error("expected 'Device Registered' heading on success")
	}

	// Device name shown
	if !strings.Contains(resp, "gaming-pc") {
		t.Error("expected device name on success screen")
	}

	// Bearer token should be rendered as a <code> block
	if !strings.Contains(resp, "<code>") {
		t.Error("expected bearer token in <code> block on success")
	}

	// Copy warning
	if !strings.Contains(resp, "copy now") && !strings.Contains(resp, "will not be shown again") {
		t.Error("expected copy warning text on success screen")
	}

	// Action links
	if !strings.Contains(resp, "View device") {
		t.Error("expected 'View device' link on success screen")
	}
	if !strings.Contains(resp, "Back to dashboard") {
		t.Error("expected 'Back to dashboard' link on success screen")
	}

	// Page title
	if !strings.Contains(resp, "<title>pcontrol — Device Registered</title>") {
		t.Error("expected page title 'pcontrol — Device Registered'")
	}
}

func TestDashboard_WithDevice(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	// Create a device via the register form
	sessionCookie := loginSession(t, mux)
	body := "name=test-phone"
	req := httptest.NewRequest(http.MethodPost, "/devices/new", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 after device registration, got %d", rec.Code)
	}

	// Dashboard should now show the device
	dashReq := httptest.NewRequest(http.MethodGet, "/", nil)
	dashReq.AddCookie(sessionCookie)
	dashRec := httptest.NewRecorder()
	mux.ServeHTTP(dashRec, dashReq)

	if dashRec.Code != http.StatusOK {
		t.Errorf("expected 200 for dashboard with device, got %d", dashRec.Code)
	}
	bodyStr := dashRec.Body.String()
	if !strings.Contains(bodyStr, "test-phone") {
		t.Error("expected device name on dashboard")
	}
	if !strings.Contains(bodyStr, "0m") {
		t.Error("expected '0m' usage on dashboard for new device")
	}
	if !strings.Contains(bodyStr, "Last usage report: never") {
		t.Error("expected never as last usage report for a device that has not reported")
	}
}

func TestDashboard_ShowsLastUsageReportTime(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("reporting-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	reportedAt := time.Date(2026, 7, 12, 14, 30, 0, 0, time.UTC)
	if err := s.TouchLastSeen(dev.ID, reportedAt); err != nil {
		t.Fatalf("TouchLastSeen: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "Last usage report: 2026-07-12T14:30:00Z") {
		t.Errorf("expected visible last usage report time, got body: %s", body)
	}
}

func TestDashboard_DeviceNewFormShown(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	req := httptest.NewRequest(http.MethodGet, "/devices/new", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 for device new form, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "Device name") {
		t.Error("expected device name input in new device form")
	}
}

// loginSession logs in and returns the session cookie for use in subsequent requests.
func loginSession(t *testing.T, mux http.Handler) *http.Cookie {
	t.Helper()
	body := "password=secret"
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	cookies := rec.Result().Cookies()
	for _, c := range cookies {
		if c.Name == "session" {
			return c
		}
	}
	t.Fatal("no session cookie returned from login")
	return nil
}

func TestDashboard_BatteryDisplay(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	// Create a device and set battery to 15% charging
	dev, _, err := s.CreateDevice("kid-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	now := time.Date(2026, 7, 6, 12, 0, 0, 0, time.UTC)
	if err := s.UpdateBatteryStatus(dev.ID, 15, true, now); err != nil {
		t.Fatalf("UpdateBatteryStatus: %v", err)
	}

	sessionCookie := loginSession(t, mux)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "15%") {
		t.Error("expected battery 15% on dashboard")
	}
	if !strings.Contains(body, "battery-low") {
		t.Error("expected battery-low class for 15%")
	}
	if !strings.Contains(body, "⚡") {
		t.Error("expected charging indicator on dashboard")
	}
}

func TestDashboard_NoBatteryWhenNil(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	// Create a device WITHOUT battery data
	dev, _, err := s.CreateDevice("no-battery-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	// Touch last_seen so the device appears, but don't set battery
	if err := s.TouchLastSeen(dev.ID, time.Now()); err != nil {
		t.Fatalf("TouchLastSeen: %v", err)
	}

	sessionCookie := loginSession(t, mux)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if strings.Contains(body, "🔋") {
		t.Error("expected NO battery indicator when battery is nil")
	}
}

func TestFormatAge(t *testing.T) {
	tests := []struct {
		age  time.Duration
		want string
	}{
		{1 * time.Minute, "1 min"},
		{2 * time.Minute, "2 min"},
		{59 * time.Minute, "59 min"},
		{60 * time.Minute, "1 h"},
		{119 * time.Minute, "1 h"},
		{120 * time.Minute, "2 h"},
		{23 * 60 * time.Minute, "23 h"},
		{24 * 60 * time.Minute, "1 d"},
		{47 * 60 * time.Minute, "1 d"},
		{48 * 60 * time.Minute, "2 d"},
		{7 * 24 * 60 * time.Minute, "7 d"},
	}
	for _, tt := range tests {
		got := formatAge(tt.age)
		if got != tt.want {
			t.Errorf("formatAge(%v) = %q, want %q", tt.age, got, tt.want)
		}
	}
}

func TestFormatDuration(t *testing.T) {
	tests := []struct {
		min  int
		want string
	}{
		{0, "0m"},
		{45, "45m"},
		{59, "59m"},
		{60, "1h"},
		{61, "1h 1m"},
		{125, "2h 5m"},
		{480, "8h"},
		{1441, "24h 1m"},
	}
	for _, tt := range tests {
		got := formatDuration(tt.min)
		if got != tt.want {
			t.Errorf("formatDuration(%d) = %q, want %q", tt.min, got, tt.want)
		}
	}
}

// TestDashboard_FormatDuration pins the human-readable duration formatting:
// a device with 480 minutes of usage today must render as "8h", not "480 min".
func TestDashboard_FormatDuration(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("duration-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	today := time.Now().UTC().Format("2006-01-02")
	events := []domain.Event{
		{EventID: "dur-1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: today, StartedAt: time.Now(), DurationSeconds: 480 * 60},
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "8h") {
		t.Errorf("expected '8h' on dashboard for 480 minutes, body: %s", body)
	}
	if strings.Contains(body, "480 min") {
		t.Error("expected no raw '480 min' on dashboard")
	}
}

// TestDeviceDetail_LimitFormatsAsDuration pins "/ 10h" on the device page
// caption when the total limit is 600 minutes.
func TestDeviceDetail_LimitFormatsAsDuration(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("limit-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	limit := 600
	if err := s.SetTotalLimit(dev.ID, &limit); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "/ 10h") {
		t.Errorf("expected '/ 10h' on device page for 600 minute limit, body: %s", body)
	}
	if strings.Contains(body, "600 min") {
		t.Error("expected no raw '600 min' on device page")
	}
}

// TestDeviceDetail_WarnTick pins the warn-threshold tick on the device page
// bar plus the progressbar ARIA attributes (Stage 2).
func TestDeviceDetail_WarnTick(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("warn-tick-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	limit := 600
	if err := s.SetTotalLimit(dev.ID, &limit); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}
	if err := s.SetWarnPercent(dev.ID, 80); err != nil {
		t.Fatalf("SetWarnPercent: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "bar-warn-tick") {
		t.Error("expected warn tick element on device page bar")
	}
	if !strings.Contains(body, "left:80%") {
		t.Error("expected warn tick positioned at left:80%")
	}
	if !strings.Contains(body, `role="progressbar"`) {
		t.Error("expected role=progressbar on device page bar")
	}
	if !strings.Contains(body, `aria-valuenow="0"`) {
		t.Error("expected aria-valuenow=0 on device page bar for zero usage")
	}
}

// TestDashboard_ProgressbarAria pins the progressbar ARIA attributes on the
// dashboard card bar (Stage 2).
func TestDashboard_ProgressbarAria(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("aria-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	limit := 600
	if err := s.SetTotalLimit(dev.ID, &limit); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `role="progressbar"`) {
		t.Error("expected role=progressbar on dashboard card bar")
	}
	if !strings.Contains(body, `aria-label="Daily usage 0 percent of limit"`) {
		t.Error("expected aria-label with percent on dashboard card bar")
	}
}

// TestDashboard_HasThemeToggle pins the dark-mode toggle button in the nav.
// The theming itself is CSS/JS and is verified by the manual smoke test.
func TestDashboard_HasThemeToggle(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `id="theme-toggle"`) {
		t.Error("expected theme toggle button in nav")
	}
}

// TestDashboard_CardIsLink pins the whole-card link (Stage 4): each device
// card is an <a class="card card-link">, the name is rendered once (no nested
// anchor inside the h2).
func TestDashboard_CardIsLink(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("link-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	wantLink := fmt.Sprintf(`<a class="card card-link" href="/devices/%d">`, dev.ID)
	if !strings.Contains(body, wantLink) {
		t.Errorf("expected whole-card link %q in dashboard body", wantLink)
	}
	if strings.Contains(body, `<h2 style="margin:0 0 0.25rem 0;font-size:1.2rem"><a href=`) {
		t.Error("expected no nested anchor inside the card heading")
	}
	if got := strings.Count(body, "link-phone"); got != 1 {
		t.Errorf("expected device name exactly once in card, got %d times", got)
	}
}

// TestDashboard_HTMXPartial pins content negotiation (Stage 5): with the
// HX-Request header the dashboard handler returns only the device grid
// partial — no layout, no nav — so HTMX can swap it in without flicker.
func TestDashboard_HTMXPartial(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	if _, _, err := s.CreateDevice("partial-phone"); err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("HX-Request", "true")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if strings.Contains(body, "<nav") {
		t.Error("HTMX partial must not contain the layout nav")
	}
	if strings.Contains(body, "<h1>") {
		t.Error("HTMX partial must not contain the page heading")
	}
	if !strings.Contains(body, "partial-phone") {
		t.Error("expected device name in HTMX partial")
	}
	if !strings.Contains(body, "status-pill") {
		t.Error("expected status pill markup in HTMX partial")
	}
	if ct := rec.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("expected text/html content type on partial, got %q", ct)
	}
}

// The dashboard content-negotiates the same URL on HX-Request (full page
// vs fragment), so every response must declare Vary: HX-Request to keep
// caches from mixing the two representations.
func TestDashboard_VaryHXRequest(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	for _, htmx := range []bool{true, false} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		if htmx {
			req.Header.Set("HX-Request", "true")
		}
		req.AddCookie(sessionCookie)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("htmx=%v: expected 200, got %d", htmx, rec.Code)
		}
		if got := rec.Header().Get("Vary"); got != "HX-Request" {
			t.Errorf("htmx=%v: expected Vary: HX-Request, got %q", htmx, got)
		}
	}
}

// TestDeviceDetail_HistoryChart pins the inline SVG 7-day chart (Stage 6):
// server-rendered bars, per-bar tooltips, and a dashed limit line when a
// total limit exists (and none when it doesn't).
func TestDeviceDetail_HistoryChart(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("chart-device")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// 7 days of fixture data: today + 6 previous days.
	now := time.Now()
	var events []domain.Event
	for i := 0; i < 7; i++ {
		day := now.AddDate(0, 0, -i).Format("2006-01-02")
		events = append(events, domain.Event{
			EventID:         fmt.Sprintf("chart-%d", i),
			DeviceID:        dev.ID,
			Kind:            domain.KindApp,
			Subject:         "com.chart.app",
			Label:           "ChartApp",
			Day:             day,
			StartedAt:       now.AddDate(0, 0, -i),
			DurationSeconds: (i + 1) * 60, // 1m..7m
		})
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}
	limit := 10
	if err := s.SetTotalLimit(dev.ID, &limit); err != nil {
		t.Fatalf("SetTotalLimit: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "<svg") {
		t.Error("expected <svg chart on device page")
	}
	if !strings.Contains(body, `role="img"`) {
		t.Error("expected role=img on the chart")
	}
	if got := strings.Count(body, "<rect"); got != 7 {
		t.Errorf("expected 7 <rect> bars, got %d", got)
	}
	if !strings.Contains(body, "<title>") {
		t.Error("expected per-bar <title> tooltips")
	}
	todayKey := now.Format("2006-01-02")
	if !strings.Contains(body, "<title>"+todayKey) {
		t.Errorf("expected a <title> containing fixture day key %s", todayKey)
	}
	if !strings.Contains(body, `<rect x="12"`) {
		t.Error("expected first bar at x=12 (chart geometry)")
	}
	if !strings.Contains(body, "<line") {
		t.Error("expected dashed limit <line> when a total limit exists")
	}

	// Without a limit there must be no limit line.
	dev2, _, err := s.CreateDevice("chart-device-nolimit")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	for i := range events {
		events[i].DeviceID = dev2.ID
		events[i].EventID = fmt.Sprintf("chart-nolimit-%d", i)
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}

	req2 := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev2.ID), nil)
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec2.Code)
	}
	if strings.Contains(rec2.Body.String(), "<line") {
		t.Error("expected no limit <line> without a total limit")
	}
}

// TestDeviceRename_HTMXToast pins the HTMX toast flow for rename (Stage 7):
// success returns a toast fragment (no redirect), validation failure returns
// a toast-error fragment with 200, and the store is only mutated on success.
func TestDeviceRename_HTMXToast(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)
	dev, _, err := s.CreateDevice("old-name")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Success: toast fragment, no redirect, store updated.
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/rename", dev.ID), strings.NewReader("name=new-name"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("HX-Request", "true")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 toast response, got %d", rec.Code)
	}
	if loc := rec.Header().Get("Location"); loc != "" {
		t.Errorf("expected no redirect for HTMX request, got Location %q", loc)
	}
	if !strings.Contains(rec.Body.String(), `class="toast"`) {
		t.Errorf("expected toast fragment in response, got: %s", rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "new-name") {
		t.Error("expected new device name in toast")
	}
	if updated, err := s.DeviceByTokenFromID(dev.ID); err != nil || updated.Name != "new-name" {
		t.Errorf("expected store rename, got %v (%v)", updated, err)
	}

	// Validation failure: toast-error fragment with 200, store unchanged.
	req2 := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/rename", dev.ID), strings.NewReader("name="))
	req2.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req2.Header.Set("HX-Request", "true")
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Errorf("expected 200 toast-error response for validation failure, got %d", rec2.Code)
	}
	if !strings.Contains(rec2.Body.String(), "toast toast-error") {
		t.Errorf("expected toast-error fragment, got: %s", rec2.Body.String())
	}
	if updated, err := s.DeviceByTokenFromID(dev.ID); err != nil || updated.Name != "new-name" {
		t.Errorf("expected name unchanged after failed rename, got %v (%v)", updated, err)
	}
}

// TestDeviceRecipients_HTMXToast pins the HTMX toast flow for recipients.
func TestDeviceRecipients_HTMXToast(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)
	dev, _, err := s.CreateDevice("toast-recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Success.
	body := "emails=" + url.QueryEscape("parent@example.com")
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/recipients", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("HX-Request", "true")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 toast response, got %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `class="toast"`) {
		t.Errorf("expected toast fragment, got: %s", rec.Body.String())
	}
	if emails, _ := s.EmailRecipients(dev.ID); len(emails) != 1 || emails[0] != "parent@example.com" {
		t.Errorf("expected recipient saved, got %v", emails)
	}

	// Validation failure: invalid email → toast-error, store unchanged.
	req2 := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/recipients", dev.ID), strings.NewReader("emails=not-an-email"))
	req2.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req2.Header.Set("HX-Request", "true")
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Errorf("expected 200 toast-error response, got %d", rec2.Code)
	}
	if !strings.Contains(rec2.Body.String(), "toast toast-error") {
		t.Errorf("expected toast-error fragment, got: %s", rec2.Body.String())
	}
	if emails, _ := s.EmailRecipients(dev.ID); len(emails) != 1 {
		t.Errorf("expected recipients unchanged after failure, got %v", emails)
	}
}

// TestDeviceTimeZone_HTMXToast pins the HTMX toast flow for timezone.
func TestDeviceTimeZone_HTMXToast(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)
	dev, _, err := s.CreateDevice("toast-tz-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Success.
	body := "timezone=" + url.QueryEscape("Europe/Berlin")
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/timezone", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("HX-Request", "true")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 toast response, got %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `class="toast"`) {
		t.Errorf("expected toast fragment, got: %s", rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "Europe/Berlin") {
		t.Error("expected saved timezone echoed in toast")
	}
	if tz, _ := s.TimeZone(dev.ID); tz != "Europe/Berlin" {
		t.Errorf("expected timezone saved, got %q", tz)
	}

	// Validation failure: invalid timezone → toast-error, store unchanged.
	req2 := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/timezone", dev.ID), strings.NewReader("timezone=Not/AZone"))
	req2.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req2.Header.Set("HX-Request", "true")
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Errorf("expected 200 toast-error response, got %d", rec2.Code)
	}
	if !strings.Contains(rec2.Body.String(), "toast toast-error") {
		t.Errorf("expected toast-error fragment, got: %s", rec2.Body.String())
	}
	if tz, _ := s.TimeZone(dev.ID); tz != "Europe/Berlin" {
		t.Errorf("expected timezone unchanged after failure, got %q", tz)
	}
}

func TestFriendlyLabel(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"com.google.android.youtube", "YouTube"},
		{"com.android.chrome", "Chrome"},
		{"com.google.android.gm", "Gmail"},
		{"com.zhiliaoapp.musically", "TikTok"},
		{"com.instagram.android", "Instagram"},
		{"com.mojang.minecraftpe", "Minecraft"},
		{"com.example.superapp", "superapp"}, // 3 parts, unknown → last part
		{"Game", "Game"},                     // ≤2 parts → unchanged
		{"a.b", "a.b"},
		{"", ""},
	}
	for _, tc := range cases {
		if got := friendlyLabel(tc.in); got != tc.want {
			t.Errorf("friendlyLabel(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestDashboard_FriendlyAppLabels pins Stage 8: a top-app pill for a known
// package shows the friendly label as visible text; the raw package stays
// available as hover-only debug info in the title attribute.
func TestDashboard_FriendlyAppLabels(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("labels-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	today := time.Now().UTC().Format("2006-01-02")
	events := []domain.Event{
		// No client-provided label: the raw package must still render friendly.
		{EventID: "lbl-1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.google.android.youtube", Label: "", Day: today, StartedAt: time.Now(), DurationSeconds: 120 * 60},
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "YouTube (2h)") {
		t.Error("expected friendly label 'YouTube' in top-app pill")
	}
	if !strings.Contains(body, `title="com.google.android.youtube"`) {
		t.Error("expected raw package preserved in title attribute")
	}
}

func TestDashboard_OnlineBadge(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("online-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	// Touch last_seen with current time (just now)
	if err := s.TouchLastSeen(dev.ID, time.Now()); err != nil {
		t.Fatalf("TouchLastSeen: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "● online") {
		t.Error("expected 'online' badge on dashboard for recently seen device")
	}
	if strings.Contains(body, "● offline") {
		t.Error("did not expect 'offline' badge for recently seen device")
	}
}

func TestDashboard_OfflineBadge(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	dev, _, err := s.CreateDevice("offline-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	// Touch last_seen with an hour ago
	if err := s.TouchLastSeen(dev.ID, time.Now().Add(-1*time.Hour)); err != nil {
		t.Fatalf("TouchLastSeen: %v", err)
	}

	sessionCookie := loginSession(t, mux)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "● offline") {
		t.Error("expected 'offline' badge on dashboard for device seen long ago")
	}
	if strings.Contains(body, "● online") {
		t.Error("did not expect 'online' badge for device seen long ago")
	}
	if !strings.Contains(body, "1 h") {
		t.Errorf("expected '1 h' age on dashboard for device seen 1h ago, body: %s", body)
	}
}

func TestDeviceRename(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("old-name")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/rename", dev.ID), strings.NewReader("name=new-name"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	// Expect redirect back to device page
	if rec.Code != http.StatusSeeOther && rec.Code != http.StatusFound && rec.Code != http.StatusOK {
		t.Errorf("expected redirect (3xx), got %d", rec.Code)
	}

	// Verify through the store that the name changed
	updated, err := s.DeviceByTokenFromID(dev.ID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}
	if updated.Name != "new-name" {
		t.Errorf("expected name 'new-name', got %q", updated.Name)
	}
}

func TestValidEmail(t *testing.T) {
	cases := []struct {
		name  string
		email string
		want  bool
	}{
		{"valid", "parent@example.com", true},
		{"missing at", "parent.example.com", false},
		{"missing dot", "parent@examplecom", false},
		{"empty local", "@example.com", false},
		{"empty domain", "parent@", false},
		{"empty", "", false},
		{"whitespace only", "   ", false},
		{"crlf injection", "parent@example.com\r\nBcc: evil@example.com", false},
		{"newline injection", "parent@example.com\nevil@example.com", false},
		{"space in domain", "parent@ex ample.com", false},
		{"space in local", "pa rent@example.com", false},
		{"tab in address", "parent@example.com\tx", false},
		{"comma-separated list", "a@b.com,c@d.com", false},
		{"multiple at signs", "a@b@c.com", false},
	}
	for _, tc := range cases {
		if got := validEmail(tc.email); got != tc.want {
			t.Errorf("validEmail(%q) = %v, want %v", tc.email, got, tc.want)
		}
	}
}

func TestDeviceRename_RejectsCRLF(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("original-name")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// A device name flows into the email Subject header; CRLF must be
	// rejected so it cannot inject headers into the daily report.
	body := "name=kid%0d%0aBcc%3A%20evil%40example.com"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/rename", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for CRLF in name, got %d", rec.Code)
	}

	updated, err := s.DeviceByTokenFromID(dev.ID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}
	if updated.Name != "original-name" {
		t.Errorf("expected name unchanged, got %q", updated.Name)
	}
}

func TestDeviceDelete(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("delete-me")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/delete", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	// Expect redirect to dashboard
	if rec.Code != http.StatusSeeOther && rec.Code != http.StatusFound && rec.Code != http.StatusOK {
		t.Errorf("expected redirect (3xx), got %d", rec.Code)
	}

	// Device should be gone
	if _, err := s.DeviceByTokenFromID(dev.ID); err == nil {
		t.Error("expected error fetching deleted device")
	}
}

func TestDeviceDetail_ShowsHistory(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	// Create a device with events across 3 days
	dev, _, err := s.CreateDevice("history-device")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Insert events on multiple days
	now := time.Now()
	events := []domain.Event{
		{EventID: "h1", DeviceID: dev.ID, Kind: domain.KindApp, Subject: "com.game", Label: "Game", Day: now.Format("2006-01-02"), StartedAt: now, DurationSeconds: 120},
	}
	// Add events on previous days
	for i := 1; i <= 3; i++ {
		day := now.AddDate(0, 0, -i).Format("2006-01-02")
		events = append(events, domain.Event{
			EventID:         fmt.Sprintf("h%d", i+1),
			DeviceID:        dev.ID,
			Kind:            domain.KindApp,
			Subject:         "com.game",
			Label:           "Game",
			Day:             day,
			StartedAt:       now.AddDate(0, 0, -i),
			DurationSeconds: 60 * i,
		})
	}
	if err := s.InsertEvents(events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}

	// Set a limit so we can check the day query selects the most recent day
	if _, err := s.SetLimit(dev.ID, "app", "com.game", 120); err != nil {
		t.Fatalf("SetLimit: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}

	body := rec.Body.String()
	// Should show 7 day labels
	if !strings.Contains(body, "7-day history") && !strings.Contains(body, "History") {
		t.Error("expected history section on device detail page")
	}
	// Should show day labels in the history
	if !strings.Contains(body, "0m") {
		t.Error("expected at least some day with '0m' in history")
	}
	if !strings.Contains(body, "1m") || !strings.Contains(body, "2m") || !strings.Contains(body, "3m") {
		t.Errorf("expected day entries with various minute values")
	}
}

func TestLoginPage_HasMinimalLayout(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	req := httptest.NewRequest(http.MethodGet, "/login", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()

	// Should render the minimal layout — no nav elements
	if strings.Contains(body, "Register device") {
		t.Error("login page should not contain nav link 'Register device'")
	}
	if strings.Contains(body, "Logout") {
		t.Error("login page should not contain 'Logout' button")
	}
	if strings.Contains(body, "class=\"nav-admin\"") {
		t.Error("login page should not include nav admin indicator")
	}

	// Should not include the footer
	if strings.Contains(body, "pcontrol ·") {
		t.Error("login page should not include footer")
	}

	// Title should be specific
	if !strings.Contains(body, "<title>pcontrol — Sign in</title>") {
		t.Error("login page should have page title 'pcontrol — Sign in'")
	}
}

func TestDashboardPage_HasNavAndFooter(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()

	// Dashboard should have nav and footer
	if !strings.Contains(body, "Register device") {
		t.Error("dashboard page should contain nav link 'Register device'")
	}
	if !strings.Contains(body, "Logout") {
		t.Error("dashboard page should contain 'Logout' button")
	}

	// Check title
	if !strings.Contains(body, "<title>pcontrol — Devices</title>") {
		t.Error("dashboard page should have page title 'pcontrol — Devices'")
	}
}

func TestDeviceDetailPage_HasDeviceNameInTitle(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("my-tablet")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()

	// Title should include the device name
	if !strings.Contains(body, "<title>pcontrol — my-tablet</title>") {
		t.Error("device detail page should have page title 'pcontrol — my-tablet'")
	}
}

func TestLimitsPage_HasDeviceNameInTitle(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("limiter-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()

	// Title should include the device name
	if !strings.Contains(body, "<title>pcontrol — Limits · limiter-phone</title>") {
		t.Error("limits page should have page title 'pcontrol — Limits · limiter-phone'")
	}
}

func TestDeviceDetail_ShowsDevicePage(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	// Create a device first
	dev, _, err := s.CreateDevice("tablet")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Access the real device
	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 for device detail, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "tablet") {
		t.Errorf("expected device name 'tablet' on device detail page, got body: %s", body[:min(len(body), 500)])
	}
	if !strings.Contains(body, "Manage limits") {
		t.Error("expected 'Manage limits' link on device detail page")
	}
}

func TestDeviceDetail_ShowsReportSettings(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("report-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	if err := s.SetEmailRecipients(dev.ID, []string{"parent@example.com", "dad@example.org"}); err != nil {
		t.Fatalf("SetEmailRecipients: %v", err)
	}
	if err := s.SetTimeZone(dev.ID, "America/New_York"); err != nil {
		t.Fatalf("SetTimeZone: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `name="timezone"`) {
		t.Error("expected timezone input on device settings")
	}
	if !strings.Contains(body, `value="America/New_York"`) {
		t.Error("expected configured timezone value in timezone input")
	}
	if !strings.Contains(body, `name="emails"`) {
		t.Error("expected report recipients textarea on device settings")
	}
	if !strings.Contains(body, "parent@example.com") || !strings.Contains(body, "dad@example.org") {
		t.Error("expected configured recipients shown in recipients textarea")
	}
}

func TestDeviceRecipients_Persist(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	body := "emails=" + url.QueryEscape("parent@example.com\ndad@example.org")
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/recipients", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther && rec.Code != http.StatusFound {
		t.Errorf("expected redirect after saving recipients, got %d", rec.Code)
	}

	got, err := s.EmailRecipients(dev.ID)
	if err != nil {
		t.Fatalf("EmailRecipients: %v", err)
	}
	if len(got) != 2 || got[0] != "parent@example.com" || got[1] != "dad@example.org" {
		t.Errorf("unexpected recipients after save: %v", got)
	}
}

func TestDeviceRecipients_InvalidEmailRejected(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("recipient-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	body := "emails=not-an-email"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/recipients", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for invalid email, got %d", rec.Code)
	}
}

func TestDeviceTimeZone_Persist(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("tz-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	body := "timezone=" + url.QueryEscape("Europe/Berlin")
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/timezone", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther && rec.Code != http.StatusFound {
		t.Errorf("expected redirect after saving timezone, got %d", rec.Code)
	}

	tz, err := s.TimeZone(dev.ID)
	if err != nil {
		t.Fatalf("TimeZone: %v", err)
	}
	if tz != "Europe/Berlin" {
		t.Errorf("expected timezone 'Europe/Berlin', got %q", tz)
	}
}

func TestDeviceTimeZone_InvalidRejected(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("tz-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	body := "timezone=" + url.QueryEscape("Not/AZone")
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/timezone", dev.ID), strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for invalid timezone, got %d", rec.Code)
	}
}

func TestDeviceReportSettings_MissingDevice404(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	// Recipients POST for a device that does not exist → 404, no orphan rows.
	body := "emails=" + url.QueryEscape("parent@example.com")
	req := httptest.NewRequest(http.MethodPost, "/devices/99999/recipients", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("expected 404 for recipients on missing device, got %d", rec.Code)
	}
	if emails, _ := s.EmailRecipients(99999); len(emails) != 0 {
		t.Errorf("expected no orphan recipients, got %v", emails)
	}

	// Timezone POST for a device that does not exist → 404.
	body = "timezone=" + url.QueryEscape("America/New_York")
	req = httptest.NewRequest(http.MethodPost, "/devices/99999/timezone", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("expected 404 for timezone on missing device, got %d", rec.Code)
	}
	if tz, _ := s.TimeZone(99999); tz != "" {
		t.Errorf("expected no orphan timezone, got %q", tz)
	}
}

func TestDeviceSettings_RejectNonNumericID(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("numeric-prefix-phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// A numeric prefix followed by junk must be rejected, not silently parsed
	// as the real device (fmt.Sscanf accepts "123abc" as 123).
	id := fmt.Sprintf("%dabc", dev.ID)

	body := "emails=" + url.QueryEscape("parent@example.com")
	req := httptest.NewRequest(http.MethodPost, "/devices/"+id+"/recipients", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("recipients: expected 400 for non-numeric device id %q, got %d", id, rec.Code)
	}

	body = "timezone=" + url.QueryEscape("America/New_York")
	req = httptest.NewRequest(http.MethodPost, "/devices/"+id+"/timezone", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("timezone: expected 400 for non-numeric device id %q, got %d", id, rec.Code)
	}
}
