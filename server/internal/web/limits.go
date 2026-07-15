package web

import (
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net/http"
)

func (h *webAuthHandler) limitsPage() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)

		policy, err := h.store.GetPolicy(deviceID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		// Gather known subjects for the <datalist>
		subjects, _ := h.store.DistinctSubjects(deviceID)

		totalText := "none"
		if policy.TotalDailyLimitMin != nil {
			totalText = fmt.Sprintf("%d minutes", *policy.TotalDailyLimitMin)
		}

		device, err := h.store.DeviceByTokenFromID(deviceID)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				http.Error(w, "device not found", http.StatusNotFound)
			} else {
				log.Printf("device lookup: %v", err)
				http.Error(w, "internal error", http.StatusInternalServerError)
			}
			return
		}

		data := limitsData{
			ID:             deviceID,
			Name:           device.Name,
			TotalLimitText: totalText,
			WarnPct:        policy.WarnThresholdPercent,
			Subjects:       make([]subjectOption, 0, len(subjects)),
		}

		for _, l := range policy.Limits {
			data.Limits = append(data.Limits, limitRow{
				ID:                l.ID,
				Kind:              string(l.Kind),
				Subject:           l.Subject,
				DailyLimitMinutes: l.DailyLimitMinutes,
			})
		}
		if data.Limits == nil {
			data.Limits = []limitRow{}
		}

		for _, e := range policy.Exclusions {
			data.Exclusions = append(data.Exclusions, exclusionRow{
				ID:      e.ID,
				Kind:    string(e.Kind),
				Subject: e.Subject,
			})
		}
		if data.Exclusions == nil {
			data.Exclusions = []exclusionRow{}
		}

		for _, s := range subjects {
			label := s.Label
			if label == "" {
				label = s.Subject
			}
			data.Subjects = append(data.Subjects, subjectOption{
				Value: s.Subject,
				Label: label,
			})
		}

		if err := renderPage(w, "limits.gohtml", data); err != nil {
			log.Printf("render limits: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
		}
	}
}

func (h *webAuthHandler) addLimit() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := parseID(r.PathValue("id"))
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		kind := r.FormValue("kind")
		subject := r.FormValue("subject")
		minutes := parseInt(r.FormValue("minutes"))

		limit, err := h.store.SetLimit(deviceID, kindToDomain(kind), subject, minutes)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		if isHTMX(r) {
			// Return the new row for HTMX to append.
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			fmt.Fprintf(w, `<tr id="limit-%d"><td>%s</td><td>%s</td><td>%d</td><td><button hx-post="/devices/%d/limits/%d/delete" hx-target="#limit-%d" hx-swap="outerHTML" class="btn-link">Delete</button></td></tr>`,
				limit.ID, htmlEsc(kind), htmlEsc(subject), minutes, deviceID, limit.ID, limit.ID)
			return
		}

		http.Redirect(w, r, fmt.Sprintf("/devices/%d/limits", deviceID), http.StatusSeeOther)
	}
}

func (h *webAuthHandler) deleteLimit() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := parseID(r.PathValue("id"))
		limitID := parseID(r.PathValue("limitId"))

		if err := h.store.DeleteLimit(limitID); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		if isHTMX(r) {
			// Return empty to remove the row via outerHTML swap.
			w.WriteHeader(http.StatusOK)
			return
		}

		http.Redirect(w, r, fmt.Sprintf("/devices/%d/limits", deviceID), http.StatusSeeOther)
	}
}

func (h *webAuthHandler) addExclusion() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := parseID(r.PathValue("id"))
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		kind := r.FormValue("kind")
		subject := r.FormValue("subject")

		exc, err := h.store.AddExclusion(deviceID, kindToDomain(kind), subject)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		if isHTMX(r) {
			// Return the new row for HTMX to append.
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			fmt.Fprintf(w, `<tr id="exclusion-%d"><td>%s</td><td>%s</td><td><button hx-post="/devices/%d/exclusions/%d/delete" hx-target="#exclusion-%d" hx-swap="outerHTML" class="btn-link">Delete</button></td></tr>`,
				exc.ID, htmlEsc(kind), htmlEsc(subject), deviceID, exc.ID, exc.ID)
			return
		}

		http.Redirect(w, r, fmt.Sprintf("/devices/%d/limits", deviceID), http.StatusSeeOther)
	}
}

func (h *webAuthHandler) deleteExclusion() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := parseID(r.PathValue("id"))
		exclusionID := parseID(r.PathValue("exclusionId"))

		if err := h.store.DeleteExclusion(exclusionID); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		if isHTMX(r) {
			w.WriteHeader(http.StatusOK)
			return
		}

		http.Redirect(w, r, fmt.Sprintf("/devices/%d/limits", deviceID), http.StatusSeeOther)
	}
}

// isHTMX reports whether the request is an HTMX AJAX request.
func isHTMX(r *http.Request) bool {
	return r.Header.Get("HX-Request") == "true"
}

func (h *webAuthHandler) updateSettings() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := parseID(r.PathValue("id"))
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		totalStr := r.FormValue("total")
		if totalStr != "" {
			total := parseInt(totalStr)
			if err := h.store.SetTotalLimit(deviceID, &total); err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		} else {
			if err := h.store.SetTotalLimit(deviceID, nil); err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}

		warnStr := r.FormValue("warn")
		if warnStr != "" {
			warn := parseInt(warnStr)
			if err := h.store.SetWarnPercent(deviceID, warn); err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}

		http.Redirect(w, r, fmt.Sprintf("/devices/%d/limits", deviceID), http.StatusSeeOther)
	}
}
