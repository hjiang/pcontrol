package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"pcontrol/server/internal/store"
)

func TestAuthMiddleware_MissingHeader(t *testing.T) {
	s := newTestAPIStore(t)
	mw := AuthMiddleware(s)

	handler := mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
}

func TestAuthMiddleware_UnknownToken(t *testing.T) {
	s := newTestAPIStore(t)
	mw := AuthMiddleware(s)

	handler := mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer this-is-not-a-valid-token")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
}

func TestAuthMiddleware_ValidToken(t *testing.T) {
	s := newTestAPIStore(t)
	_, rawToken := createTestDevice(t, s, "phone-1")

	mw := AuthMiddleware(s)
	var gotDeviceID int64
	handler := mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotDeviceID = r.Context().Value(deviceIDKey).(int64)
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer "+rawToken)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	if gotDeviceID == 0 {
		t.Error("expected non-zero device ID in context")
	}
}

func TestAuthMiddleware_WrongScheme(t *testing.T) {
	s := newTestAPIStore(t)
	mw := AuthMiddleware(s)

	handler := mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Basic dXNlcjpwYXNz")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for wrong scheme, got %d", rec.Code)
	}
}

// --- helpers ---

func newTestAPIStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatalf("Open test store: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func createTestDevice(t *testing.T, s *store.Store, name string) (int64, string) {
	t.Helper()
	dev, raw, err := s.CreateDevice(name)
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	return dev.ID, raw
}
