package web

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
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
	if !strings.Contains(body, "Limits") {
		t.Error("expected 'Limits' link on device detail page")
	}
}

