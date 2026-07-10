package web

import (
	"crypto/tls"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"pcontrol/server/internal/store"
)

func TestLogin_WrongPassword(t *testing.T) {
	s := newTestWebStore(t)
	mux := NewRouter(s, "$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha")

	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader("password=wrong"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "password") {
		t.Error("expected login form in 401 response")
	}
}

// TestLoginFlow tests the full login-logout-redirect cycle.
func TestLoginFlow(t *testing.T) {
	s := newTestWebStore(t)

	realHash := testBcryptHash(t, "secretpassword")
	mux := NewRouter(s, realHash)

	// Try to access dashboard without login → redirect to /login
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther && rec.Code != http.StatusFound {
		t.Errorf("expected redirect (303 or 302) for unauthenticated request, got %d", rec.Code)
	}

	// Login with correct password
	body := "password=secretpassword"
	loginReq := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	loginRec := httptest.NewRecorder()
	mux.ServeHTTP(loginRec, loginReq)

	if loginRec.Code != http.StatusSeeOther && loginRec.Code != http.StatusFound {
		t.Errorf("expected redirect after successful login, got %d", loginRec.Code)
	}

	// Check that a session cookie was set
	cookies := loginRec.Result().Cookies()
	var sessionCookie *http.Cookie
	for _, c := range cookies {
		if c.Name == "session" {
			sessionCookie = c
			break
		}
	}
	if sessionCookie == nil {
		t.Fatal("expected session cookie after login")
	}
	if !sessionCookie.HttpOnly {
		t.Error("expected HttpOnly session cookie")
	}

	// Use the session cookie to access dashboard
	dashReq := httptest.NewRequest(http.MethodGet, "/", nil)
	dashReq.AddCookie(sessionCookie)
	dashRec := httptest.NewRecorder()
	mux.ServeHTTP(dashRec, dashReq)

	if dashRec.Code != http.StatusOK {
		t.Errorf("expected 200 for authenticated dashboard, got %d", dashRec.Code)
	}

	// Logout
	logoutReq := httptest.NewRequest(http.MethodPost, "/logout", nil)
	logoutReq.AddCookie(sessionCookie)
	logoutRec := httptest.NewRecorder()
	mux.ServeHTTP(logoutRec, logoutReq)

	if logoutRec.Code != http.StatusSeeOther && logoutRec.Code != http.StatusFound {
		t.Errorf("expected redirect after logout, got %d", logoutRec.Code)
	}

	// Old session cookie should no longer work
	dashReq2 := httptest.NewRequest(http.MethodGet, "/", nil)
	dashReq2.AddCookie(sessionCookie)
	dashRec2 := httptest.NewRecorder()
	mux.ServeHTTP(dashRec2, dashReq2)

	if dashRec2.Code != http.StatusSeeOther && dashRec2.Code != http.StatusFound {
		t.Errorf("expected redirect after session deleted, got %d", dashRec2.Code)
	}
}

// TestLoginCookieNotSecureOverHTTP asserts that a login over plain HTTP
// (r.TLS == nil) sets a session cookie WITHOUT the Secure flag. A browser
// silently discards Secure cookies on http://, so a hard-coded Secure=true
// makes login impossible on a LAN-only Unraid deployment started as
// http://unraid-ip:7285/.
func TestLoginCookieNotSecureOverHTTP(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secretpassword")
	mux := NewRouter(s, realHash)

	body := "password=secretpassword"
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	// r.TLS is nil → simulates plain HTTP (httptest.NewRequest default).
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther && rec.Code != http.StatusFound {
		t.Fatalf("expected redirect after successful login, got %d", rec.Code)
	}

	sessionCookie := findSessionCookie(t, rec.Result().Cookies())
	if sessionCookie.Secure {
		t.Error("expected session cookie Secure=false over plain HTTP (r.TLS == nil); " +
			"a browser on http:// would discard a Secure cookie, blocking login")
	}
}

// TestLoginCookieSecureOverHTTPS asserts that a login over HTTPS
// (r.TLS != nil) sets the Secure flag so the cookie stays off the wire
// on any accidental plain-HTTP request.
func TestLoginCookieSecureOverHTTPS(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secretpassword")
	mux := NewRouter(s, realHash)

	body := "password=secretpassword"
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.TLS = &tls.ConnectionState{} // simulate an HTTPS request
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	sessionCookie := findSessionCookie(t, rec.Result().Cookies())
	if !sessionCookie.Secure {
		t.Error("expected session cookie Secure=true over HTTPS (r.TLS != nil)")
	}
}

func TestLogin_WrongPasswordRendersForm(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	body := "password=wrongpassword"
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "password") {
		t.Error("expected login form in 401 response")
	}
}

// --- helpers ---

func findSessionCookie(t *testing.T, cookies []*http.Cookie) *http.Cookie {
	t.Helper()
	for _, c := range cookies {
		if c.Name == "session" {
			return c
		}
	}
	t.Fatal("expected a session cookie")
	return nil
}

func newTestWebStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func testBcryptHash(t *testing.T, password string) string {
	t.Helper()
	hashBytes, err := bcryptHash(password)
	if err != nil {
		t.Fatalf("bcrypt hash: %v", err)
	}
	return hashBytes
}
