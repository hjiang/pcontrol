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
	if !strings.Contains(bodyStr, "0 min") {
		t.Error("expected '0 min' usage on dashboard for new device")
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
	if !strings.Contains(body, "0 min") {
		t.Error("expected at least some day with '0 min' in history")
	}
	if !strings.Contains(body, "1 min") || !strings.Contains(body, "2 min") || !strings.Contains(body, "3 min") {
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
