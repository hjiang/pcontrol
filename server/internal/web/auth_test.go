package web

import (
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
