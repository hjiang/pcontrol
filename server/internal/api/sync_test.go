package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"pcontrol/server/internal/store"
)

func TestSync_HappyPath(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	body := fmt.Sprintf(`{
		"device_time": "2026-07-03T15:04:05+08:00",
		"policy_version": 0,
		"events": [
			{"event_id": "u1", "kind": "app", "subject": "com.game", "label": "Game", "day": "2026-07-03", "started_at": "2026-07-03T14:58:00+08:00", "duration_seconds": 120}
		]
	}`)

	rec := doSyncRequest(t, handler, rawToken, body)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp syncResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}

	if len(resp.AcceptedEventIDs) != 1 || resp.AcceptedEventIDs[0] != "u1" {
		t.Errorf("expected accepted [u1], got %v", resp.AcceptedEventIDs)
	}
	if resp.Policy == nil {
		t.Fatal("expected non-nil policy on first sync")
	}
	if resp.Policy.Version != 1 {
		t.Errorf("expected policy version 1, got %d", resp.Policy.Version)
	}
}

func TestSync_DuplicateEventsIgnored(t *testing.T) {
	s, handler, rawToken, deviceID := newTestSyncEnvFull(t)

	body := fmt.Sprintf(`{
		"device_time": "2026-07-03T15:04:05+08:00",
		"policy_version": 0,
		"events": [
			{"event_id": "u1", "kind": "app", "subject": "com.game", "label": "Game", "day": "2026-07-03", "started_at": "2026-07-03T14:58:00+08:00", "duration_seconds": 120}
		]
	}`)

	// Send same batch twice
	for i := 0; i < 2; i++ {
		rec := doSyncRequest(t, handler, rawToken, body)
		if rec.Code != http.StatusOK {
			t.Fatalf("attempt %d: expected 200, got %d", i+1, rec.Code)
		}
	}

	apps, web, err := s.UsageTotals(deviceID, "2026-07-03")
	if err != nil {
		t.Fatalf("UsageTotals: %v", err)
	}
	var total int
	for _, a := range apps {
		total += a.Seconds
	}
	for _, w := range web {
		total += w.Seconds
	}
	if total != 120 {
		t.Errorf("expected total 120 (single event), got %d", total)
	}
}

func TestSync_SamePolicyVersionReturnsNull(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	// First sync with version 0 gets the policy
	body1 := `{"device_time":"2026-07-03T00:00:00Z","policy_version":0,"events":[]}`
	rec1 := doSyncRequest(t, handler, rawToken, body1)

	var resp1 syncResponse
	json.Unmarshal(rec1.Body.Bytes(), &resp1)
	if resp1.Policy == nil {
		t.Fatal("expected non-nil policy on first sync")
	}

	// Second sync with the version we just got back
	body2 := fmt.Sprintf(`{"device_time":"2026-07-03T00:00:00Z","policy_version":%d,"events":[]}`, resp1.Policy.Version)
	rec2 := doSyncRequest(t, handler, rawToken, body2)

	var resp2 syncResponse
	json.Unmarshal(rec2.Body.Bytes(), &resp2)
	if resp2.Policy != nil {
		t.Errorf("expected null policy when version matches, got %+v", resp2.Policy)
	}
}

func TestSync_MalformedJSON(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	rec := doSyncRequest(t, handler, rawToken, `{invalid json`)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", rec.Code)
	}
}

func TestSync_OversizedBody(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	big := strings.Repeat("x", 2*1024*1024)
	body := `{"event":"` + big + `"}`
	rec := doSyncRequest(t, handler, rawToken, body)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("expected 413, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestSync_InvalidEventKindReturns400(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	body := `{
		"device_time": "2026-07-03T15:04:05+08:00",
		"policy_version": 0,
		"events": [
			{"event_id": "u1", "kind": "invalid_kind", "subject": "com.game", "label": "Game", "day": "2026-07-03", "started_at": "2026-07-03T14:58:00+08:00", "duration_seconds": 120}
		]
	}`

	rec := doSyncRequest(t, handler, rawToken, body)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for invalid kind, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestSync_MissingEventIdReturns400(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	body := `{
		"device_time": "2026-07-03T15:04:05+08:00",
		"policy_version": 0,
		"events": [
			{"event_id": "", "kind": "app", "subject": "com.game", "label": "Game", "day": "2026-07-03", "started_at": "2026-07-03T14:58:00+08:00", "duration_seconds": 120}
		]
	}`

	rec := doSyncRequest(t, handler, rawToken, body)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for empty event_id, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestSync_Unauthenticated(t *testing.T) {
	_, handler, _ := newTestSyncEnv(t)

	rec := doRawSyncRequest(t, handler, "bad-token", `{}`)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
}

func TestSync_BatteryPersisted(t *testing.T) {
	s, handler, rawToken, deviceID := newTestSyncEnvFull(t)

	body := `{
		"device_time": "2026-07-06T00:00:00Z",
		"policy_version": 0,
		"events": [],
		"battery_percent": 42,
		"battery_charging": true
	}`

	rec := doSyncRequest(t, handler, rawToken, body)
	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	dev, err := s.DeviceByTokenFromID(deviceID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}
	if dev.BatteryPercent == nil || *dev.BatteryPercent != 42 {
		t.Errorf("expected BatteryPercent 42, got %v", dev.BatteryPercent)
	}
	if dev.BatteryCharging == nil || !*dev.BatteryCharging {
		t.Errorf("expected BatteryCharging true, got %v", dev.BatteryCharging)
	}
	if dev.BatteryUpdatedAt == nil {
		t.Error("expected BatteryUpdatedAt to be set")
	}
}

func TestSync_WithoutBatteryFieldLeavesExistingBattery(t *testing.T) {
	s, handler, rawToken, deviceID := newTestSyncEnvFull(t)

	// First sync with battery
	body1 := `{"device_time":"2026-07-06T00:00:00Z","policy_version":0,"events":[],"battery_percent":42,"battery_charging":true}`
	rec1 := doSyncRequest(t, handler, rawToken, body1)
	if rec1.Code != http.StatusOK {
		t.Fatalf("first sync: expected 200, got %d", rec1.Code)
	}

	// Second sync without battery fields
	body2 := `{"device_time":"2026-07-06T01:00:00Z","policy_version":1,"events":[]}`
	rec2 := doSyncRequest(t, handler, rawToken, body2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("second sync: expected 200, got %d", rec2.Code)
	}

	dev, err := s.DeviceByTokenFromID(deviceID)
	if err != nil {
		t.Fatalf("DeviceByTokenFromID: %v", err)
	}
	if dev.BatteryPercent == nil || *dev.BatteryPercent != 42 {
		t.Errorf("expected BatteryPercent still 42, got %v", dev.BatteryPercent)
	}
}

func TestSync_InvalidBatteryPercentReturns400(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	for _, val := range []int{101, -1} {
		body := fmt.Sprintf(`{
			"device_time": "2026-07-06T00:00:00Z",
			"policy_version": 0,
			"events": [],
			"battery_percent": %d,
			"battery_charging": false
		}`, val)

		rec := doSyncRequest(t, handler, rawToken, body)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("battery_percent=%d: expected 400, got %d", val, rec.Code)
		}
	}
}

func TestSync_EmptyEventsWithBattery(t *testing.T) {
	_, handler, rawToken := newTestSyncEnv(t)

	body := `{
		"device_time": "2026-07-06T00:00:00Z",
		"policy_version": 0,
		"events": [],
		"battery_percent": 80,
		"battery_charging": false
	}`

	rec := doSyncRequest(t, handler, rawToken, body)
	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 for empty events with battery, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp syncResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if len(resp.AcceptedEventIDs) != 0 {
		t.Errorf("expected empty accepted_event_ids, got %v", resp.AcceptedEventIDs)
	}
}

// --- helpers ---

func newTestSyncEnv(t *testing.T) (*store.Store, http.Handler, string) {
	t.Helper()
	s, handler, token, _ := newTestSyncEnvFull(t)
	return s, handler, token
}

func newTestSyncEnvFull(t *testing.T) (*store.Store, http.Handler, string, int64) {
	t.Helper()
	s, err := store.Open(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })

	dev, rawToken, err := s.CreateDevice("phone-1")
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	mux := http.NewServeMux()
	HandleSync(s, mux)
	handler := AuthMiddleware(s)(mux)
	return s, handler, rawToken, dev.ID
}

func doSyncRequest(t *testing.T, handler http.Handler, rawToken, body string) *httptest.ResponseRecorder {
	t.Helper()
	return doRawSyncRequest(t, handler, rawToken, body)
}

func doRawSyncRequest(t *testing.T, handler http.Handler, rawToken, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sync", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+rawToken)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}
