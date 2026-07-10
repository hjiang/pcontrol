package api

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"pcontrol/server/internal/domain"
	"pcontrol/server/internal/store"
)

const maxSyncBodySize = 1 << 20 // 1 MiB

type syncRequest struct {
	DeviceTime      string      `json:"device_time"`
	PolicyVersion   int         `json:"policy_version"`
	Events          []syncEvent `json:"events"`
	BatteryPercent  *int        `json:"battery_percent"`
	BatteryCharging *bool       `json:"battery_charging"`
}

type syncEvent struct {
	EventID         string `json:"event_id"`
	Kind            string `json:"kind"`
	Subject         string `json:"subject"`
	Label           string `json:"label"`
	Day             string `json:"day"`
	StartedAt       string `json:"started_at"`
	DurationSeconds int    `json:"duration_seconds"`
}

type syncResponse struct {
	AcceptedEventIDs []string    `json:"accepted_event_ids"`
	Policy           *policyJSON `json:"policy"` // nil when version matches
}

type policyJSON struct {
	Version              int             `json:"version"`
	TotalDailyLimitMin   *int            `json:"total_daily_limit_minutes"`
	WarnThresholdPercent int             `json:"warn_threshold_percent"`
	Limits               []limitJSON     `json:"limits"`
	Exclusions           []exclusionJSON `json:"exclusions"`
}

type limitJSON struct {
	Kind    string `json:"kind"`
	Subject string `json:"subject"`
	Minutes int    `json:"daily_limit_minutes"`
}

type exclusionJSON struct {
	Kind    string `json:"kind"`
	Subject string `json:"subject"`
}

// HandleSync registers the sync endpoint on the given mux.
func HandleSync(s *store.Store, mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/sync", func(w http.ResponseWriter, r *http.Request) {
		deviceID, ok := DeviceIDFromContext(r.Context())
		if !ok {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		// Limit body size
		r.Body = http.MaxBytesReader(w, r.Body, maxSyncBodySize)

		var req syncRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			if IsMaxBytesError(err) {
				http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
			} else {
				http.Error(w, "bad request", http.StatusBadRequest)
			}
			return
		}

		// Validate battery fields — both must be present or both absent
		if req.BatteryPercent != nil || req.BatteryCharging != nil {
			if req.BatteryPercent == nil || req.BatteryCharging == nil {
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}
			if *req.BatteryPercent < 0 || *req.BatteryPercent > 100 {
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}
		}

		// Parse events
		events := make([]domain.Event, 0, len(req.Events))
		acceptedIDs := make([]string, 0, len(req.Events))
		for _, se := range req.Events {
			// Validate required fields
			if se.EventID == "" {
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}
			if se.Kind != "app" && se.Kind != "web" {
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}
			if se.Day == "" {
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}

			startedAt, err := time.Parse(time.RFC3339, se.StartedAt)
			if err != nil {
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}
			events = append(events, domain.Event{
				EventID:         se.EventID,
				DeviceID:        deviceID,
				Kind:            domain.Kind(se.Kind),
				Subject:         se.Subject,
				Label:           se.Label,
				Day:             se.Day,
				StartedAt:       startedAt,
				DurationSeconds: se.DurationSeconds,
			})
			acceptedIDs = append(acceptedIDs, se.EventID)
		}

		// Insert events (duplicates are silently ignored via ON CONFLICT DO NOTHING)
		if len(events) > 0 {
			if err := s.InsertEvents(events); err != nil {
				log.Printf("insert events: %v", err)
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		// Touch last_seen
		_ = s.TouchLastSeen(deviceID, time.Now())

		// Persist battery (best-effort, never fails the request)
		if req.BatteryPercent != nil {
			charging := false
			if req.BatteryCharging != nil {
				charging = *req.BatteryCharging
			}
			if err := s.UpdateBatteryStatus(deviceID, *req.BatteryPercent, charging, time.Now()); err != nil {
				log.Printf("update battery: %v", err)
			}
		}

		// Build response
		resp := syncResponse{
			AcceptedEventIDs: acceptedIDs,
		}

		// Get current policy version
		currentVersion, err := s.PolicyVersion(deviceID)
		if err != nil {
			currentVersion = 1
		}

		if req.PolicyVersion != currentVersion {
			policy, err := s.GetPolicy(deviceID)
			if err != nil {
				log.Printf("get policy: %v", err)
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			resp.Policy = policyToJSON(policy)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	})
}

func policyToJSON(p domain.Policy) *policyJSON {
	limits := make([]limitJSON, 0, len(p.Limits))
	for _, l := range p.Limits {
		limits = append(limits, limitJSON{Kind: string(l.Kind), Subject: l.Subject, Minutes: l.DailyLimitMinutes})
	}
	exclusions := make([]exclusionJSON, 0, len(p.Exclusions))
	for _, e := range p.Exclusions {
		exclusions = append(exclusions, exclusionJSON{Kind: string(e.Kind), Subject: e.Subject})
	}
	return &policyJSON{
		Version:              p.Version,
		TotalDailyLimitMin:   p.TotalDailyLimitMin,
		WarnThresholdPercent: p.WarnThresholdPercent,
		Limits:               limits,
		Exclusions:           exclusions,
	}
}

// IsMaxBytesError checks if an error is an http.MaxBytesError.
func IsMaxBytesError(err error) bool {
	_, ok := err.(*http.MaxBytesError)
	return ok
}
