package web

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLimitsPage_ShowsNoLimits(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "none") {
		t.Error("expected 'none' for total limit on fresh device")
	}
	if !strings.Contains(body, "Add limit") {
		t.Error("expected 'Add limit' form on limits page")
	}
	if !strings.Contains(body, "Add exclusion") {
		t.Error("expected 'Add exclusion' form on limits page")
	}
	if !strings.Contains(body, "list=\"subjects\"") {
		t.Error("expected datalist reference (list=\"subjects\") on limit input")
	}
}

func TestLimitsPage_AddAndDeleteLimit(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Add a limit
	limitBody := "kind=app&subject=com.example.app&minutes=60"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/limits", dev.ID), strings.NewReader(limitBody))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	// Should redirect back to limits page
	if rec.Code != http.StatusSeeOther {
		t.Errorf("expected redirect after adding limit, got %d", rec.Code)
	}

	// Verify limit appears on limits page
	req2 := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec2.Code)
	}
	body := rec2.Body.String()
	// Check for the subject inside a table cell (not in the input placeholder)
	if !strings.Contains(body, ">com.example.app<") {
		t.Error("expected limit subject in table on limits page after adding")
	}
	if !strings.Contains(body, ">60<") {
		t.Error("expected limit minutes in table on limits page after adding")
	}

	// Get policy to find limit ID
	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if len(policy.Limits) != 1 {
		t.Fatalf("expected 1 limit, got %d", len(policy.Limits))
	}

	// Delete the limit
	delReq := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/limits/%d/delete", dev.ID, policy.Limits[0].ID), nil)
	delReq.AddCookie(sessionCookie)
	delRec := httptest.NewRecorder()
	mux.ServeHTTP(delRec, delReq)

	if delRec.Code != http.StatusSeeOther {
		t.Errorf("expected redirect after deleting limit, got %d", delRec.Code)
	}

	// Verify limit is gone
	req3 := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req3.AddCookie(sessionCookie)
	rec3 := httptest.NewRecorder()
	mux.ServeHTTP(rec3, req3)

	body3 := rec3.Body.String()
	// Use >subject< to check table cell content, not the input placeholder
	if strings.Contains(body3, ">com.example.app<") {
		t.Error("expected limit subject to be removed from table after deletion")
	}
}


func TestLimitsPage_AddAndDeleteExclusion(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Add an exclusion
	excBody := "kind=web&subject=khanacademy.org"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/exclusions", dev.ID), strings.NewReader(excBody))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther {
		t.Errorf("expected redirect after adding exclusion, got %d", rec.Code)
	}

	// Verify exclusion appears
	req2 := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	body := rec2.Body.String()
	if !strings.Contains(body, "khanacademy.org") {
		t.Error("expected exclusion subject on limits page after adding")
	}

	// Find exclusion ID
	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if len(policy.Exclusions) != 1 {
		t.Fatalf("expected 1 exclusion, got %d", len(policy.Exclusions))
	}

	// Delete the exclusion
	delReq := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/exclusions/%d/delete", dev.ID, policy.Exclusions[0].ID), nil)
	delReq.AddCookie(sessionCookie)
	delRec := httptest.NewRecorder()
	mux.ServeHTTP(delRec, delReq)

	if delRec.Code != http.StatusSeeOther {
		t.Errorf("expected redirect after deleting exclusion, got %d", delRec.Code)
	}

	// Verify exclusion is gone
	req3 := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req3.AddCookie(sessionCookie)
	rec3 := httptest.NewRecorder()
	mux.ServeHTTP(rec3, req3)

	body3 := rec3.Body.String()
	if strings.Contains(body3, "khanacademy.org") {
		t.Error("expected exclusion subject to be removed after deletion")
	}
}

func TestLimitsPage_SetTotalLimitAndWarn(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Set total limit and warn percent
	settingsBody := "total=120&warn=80"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/settings", dev.ID), strings.NewReader(settingsBody))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther {
		t.Errorf("expected redirect after saving settings, got %d", rec.Code)
	}

	// Verify settings on limits page
	req2 := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/devices/%d/limits", dev.ID), nil)
	req2.AddCookie(sessionCookie)
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)

	body := rec2.Body.String()
	if !strings.Contains(body, "120 minutes") {
		t.Error("expected '120 minutes' for total limit on limits page")
	}
	if !strings.Contains(body, "warn at 80%") {
		t.Error("expected 'warn at 80%' on limits page")
	}
}

func TestLimitsPage_SetTotalLimitAndWarn_HTMX(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Set total limit and warn percent via HTMX
	settingsBody := "total=120&warn=80"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/settings", dev.ID), strings.NewReader(settingsBody))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("HX-Request", "true")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 for HTMX settings update, got %d", rec.Code)
	}

	body := rec.Body.String()

	// Should return the daily-limit-card fragment
	if !strings.Contains(body, `id="daily-limit-card"`) {
		t.Error("expected daily-limit-card fragment in HTMX response")
	}

	// Should reflect updated values
	if !strings.Contains(body, "120 minutes") {
		t.Error("expected '120 minutes' in HTMX response")
	}
	if !strings.Contains(body, "warn at 80%") {
		t.Error("expected 'warn at 80%' in HTMX response")
	}

	// Should include updated warn value in the input
	if !strings.Contains(body, `value="80"`) {
		t.Error("expected warn input with value=80 in HTMX response")
	}

	// Verify through store that values were persisted
	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if policy.TotalDailyLimitMin == nil || *policy.TotalDailyLimitMin != 120 {
		t.Errorf("expected total limit 120, got %v", policy.TotalDailyLimitMin)
	}
	if policy.WarnThresholdPercent != 80 {
		t.Errorf("expected warn percent 80, got %d", policy.WarnThresholdPercent)
	}
}

func TestLimitsPage_ClearTotalLimit(t *testing.T) {
	s := newTestWebStore(t)
	realHash := testBcryptHash(t, "secret")
	mux := NewRouter(s, realHash)

	sessionCookie := loginSession(t, mux)

	dev, _, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	// Set a total limit first
	s.SetTotalLimit(dev.ID, &[]int{60}[0])

	// Clear it by sending empty total
	settingsBody := "total=&warn=90"
	req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/devices/%d/settings", dev.ID), strings.NewReader(settingsBody))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(sessionCookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusSeeOther {
		t.Errorf("expected redirect after clearing total limit, got %d", rec.Code)
	}

	// Verify limit is cleared
	policy, err := s.GetPolicy(dev.ID)
	if err != nil {
		t.Fatalf("GetPolicy: %v", err)
	}
	if policy.TotalDailyLimitMin != nil {
		t.Error("expected total limit to be cleared (nil)")
	}
}
