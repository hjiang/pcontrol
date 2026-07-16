package web

import (
	"database/sql"
	"errors"
	"fmt"
	"html/template"
	"log"
	"math"
	"net/http"
	"time"

	"pcontrol/server/internal/domain"
)

const onlineThreshold = 5 * time.Minute

func (h *webAuthHandler) dashboard() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}

		// List devices
		rows, err := h.store.DB.Query(
			`SELECT id, name, created_at, COALESCE(last_seen_at, 'never'),
			        battery_percent, battery_charging
			 FROM devices ORDER BY id`)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		type rawDevice struct {
			ID              int64
			Name            string
			CreatedAt       string
			LastSeenAt      string
			BatteryPercent  sql.NullInt64
			BatteryCharging sql.NullInt64
		}
		var rawDevices []rawDevice
		for rows.Next() {
			var d rawDevice
			if err := rows.Scan(&d.ID, &d.Name, &d.CreatedAt, &d.LastSeenAt,
				&d.BatteryPercent, &d.BatteryCharging); err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			rawDevices = append(rawDevices, d)
		}
		if err := rows.Err(); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		today := time.Now().UTC().Format("2006-01-02")
		devices := make([]dashboardDeviceEntry, 0, len(rawDevices))

		for _, rd := range rawDevices {
			appTotals, webTotals, _ := h.store.UsageTotals(rd.ID, today)
			policy, _ := h.store.GetPolicy(rd.ID)

			totalSeconds := domain.CountedTotalSeconds(appTotals, webTotals, policy.Exclusions)
			totalMinutes := totalSeconds / 60

			// Compute online status
			online := false
			var lastSeenAge string
			if rd.LastSeenAt != "never" {
				if t, err := time.Parse(time.RFC3339, rd.LastSeenAt); err == nil {
					age := time.Since(t)
					if age < 0 {
						age = 0
					}
					online = age <= onlineThreshold
					lastSeenAge = formatAge(age)
				}
			}

			entry := dashboardDeviceEntry{
				ID:           rd.ID,
				Name:         rd.Name,
				LastSeenAt:   rd.LastSeenAt,
				Online:       online,
				LastSeenAge:  lastSeenAge,
				TotalMinutes: int(totalMinutes),
			}

			// Battery
			if rd.BatteryPercent.Valid {
				pct := int(rd.BatteryPercent.Int64)
				entry.HasBattery = true
				entry.BatteryPercent = pct
				entry.BatteryLow = pct < 20
				if rd.BatteryCharging.Valid {
					entry.BatteryCharging = rd.BatteryCharging.Int64 != 0
				}
			}

			if policy.TotalDailyLimitMin != nil {
				limitMin := *policy.TotalDailyLimitMin
				entry.HasLimit = true
				entry.LimitMin = limitMin
				pct := 0
				if limitMin > 0 {
					pct = int(totalMinutes) * 100 / limitMin
				}
				entry.BarPercent = min(pct, 100)
				entry.BarColor = "green"
				if int(totalMinutes) >= limitMin {
					entry.BarColor = "red"
				} else if pct >= policy.WarnThresholdPercent {
					entry.BarColor = "orange"
				}
			}

			// Collect top 3 apps/sites
			var top []topEntry
			for _, a := range appTotals {
				label := a.Label
				if label == "" {
					label = a.Subject
				}
				top = append(top, topEntry{Label: label, Minutes: a.Seconds / 60})
			}
			for _, w2 := range webTotals {
				top = append(top, topEntry{Label: w2.Label, Minutes: w2.Seconds / 60})
			}
			// Sort descending
			for i := 1; i < len(top); i++ {
				for j := i; j > 0 && top[j].Minutes > top[j-1].Minutes; j-- {
					top[j], top[j-1] = top[j-1], top[j]
				}
			}
			if len(top) > 5 {
				entry.HasMoreEntries = true
				top = top[:5]
			}
			entry.TopEntries = top

			devices = append(devices, entry)
		}

		if err := renderPage(w, "dashboard.gohtml", dashboardData{Devices: devices}); err != nil {
			log.Printf("render dashboard: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
		}
	}
}

func (h *webAuthHandler) deviceNewForm() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := renderPage(w, "register.gohtml", registerData{}); err != nil {
			log.Printf("render register form: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
		}
	}
}

func (h *webAuthHandler) deviceNew() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		name := r.FormValue("name")
		if name == "" {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}

		dev, rawToken, err := h.store.CreateDevice(name)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Cache-Control", "no-store")
		if err := renderPage(w, "register.gohtml", registerData{Success: true, DeviceName: dev.Name, Token: rawToken, DeviceID: dev.ID}); err != nil {
			log.Printf("render register success: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
	}
}

func (h *webAuthHandler) deviceRename() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)
		if deviceID <= 0 {
			http.Error(w, "invalid device id", http.StatusBadRequest)
			return
		}

		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		name := r.FormValue("name")
		if name == "" {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}

		if err := h.store.RenameDevice(deviceID, name); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				http.Error(w, "device not found", http.StatusNotFound)
				return
			}
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		http.Redirect(w, r, fmt.Sprintf("/devices/%d", deviceID), http.StatusSeeOther)
	}
}

func (h *webAuthHandler) deviceDelete() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)
		if deviceID <= 0 {
			http.Error(w, "invalid device id", http.StatusBadRequest)
			return
		}

		if err := h.store.DeleteDevice(deviceID); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				http.Error(w, "device not found", http.StatusNotFound)
				return
			}
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		http.Redirect(w, r, "/", http.StatusSeeOther)
	}
}

func (h *webAuthHandler) deviceDetail() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)
		if deviceID <= 0 {
			http.Error(w, "device not found", http.StatusNotFound)
			return
		}

		day := r.URL.Query().Get("day")
		if day == "" {
			// Default to device-local "today": the most recent day key that has
			// events for this device (§6: server trusts the day field on events).
			row := h.store.DB.QueryRow(
				`SELECT day FROM usage_events WHERE device_id = ? ORDER BY day DESC LIMIT 1`,
				deviceID)
			if err := row.Scan(&day); err != nil {
				day = time.Now().UTC().Format("2006-01-02")
			}
		}

		policy, err := h.store.GetPolicy(deviceID)
		if err != nil {
			policy = domain.Policy{}
		}

		appTotals, webTotals, _ := h.store.UsageTotals(deviceID, day)

		totalSeconds := domain.CountedTotalSeconds(appTotals, webTotals, policy.Exclusions)
		totalMinutes := totalSeconds / 60

		device, err := h.store.DeviceByTokenFromID(deviceID)
		if err != nil {
			http.Error(w, "device not found", http.StatusNotFound)
			return
		}
		data := deviceDetailData{
			ID:           deviceID,
			Name:         device.Name,
			Day:          day,
			TotalMinutes: int(totalMinutes),
		}

		// Battery
		if device.BatteryPercent != nil {
			pct := *device.BatteryPercent
			data.HasBattery = true
			data.BatteryPercent = pct
			data.BatteryLow = pct < 20
			if device.BatteryCharging != nil {
				data.BatteryCharging = *device.BatteryCharging
			}
		}

		// 7-day history: compute counted totals for the last 7 days
		if parsedDay, err := time.Parse("2006-01-02", day); err == nil {
			fromDay := parsedDay.AddDate(0, 0, -6).Format("2006-01-02")
			dailyTotals, err := h.store.DailyTotalsWithExclusions(deviceID, fromDay, day, policy.Exclusions)
			if err != nil {
				log.Printf("daily totals: %v", err)
			} else {
				// Find max for scaling
				maxMinutes := 0
				for i := 6; i >= 0; i-- {
					d := parsedDay.AddDate(0, 0, -i).Format("2006-01-02")
					min := dailyTotals[d] / 60
					if min > maxMinutes {
						maxMinutes = min
					}
				}
				if maxMinutes == 0 {
					maxMinutes = 1 // avoid division by zero
				}
				for i := 6; i >= 0; i-- {
					d := parsedDay.AddDate(0, 0, -i).Format("2006-01-02")
					min := dailyTotals[d] / 60
					pct := min * 100 / maxMinutes
					data.History = append(data.History, historyRow{
						Day:        d,
						Minutes:    min,
						BarPercent: pct,
					})
				}
			}
		}

		if policy.TotalDailyLimitMin != nil {
			limitMin := *policy.TotalDailyLimitMin
			data.HasLimit = true
			data.LimitMin = limitMin
			data.WarnPct = policy.WarnThresholdPercent
			pct := 0
			if limitMin > 0 {
				pct = int(totalMinutes) * 100 / limitMin
			}
			data.BarPercent = min(pct, 100)
			data.BarColor = "green"
			if int(totalMinutes) >= limitMin {
				data.BarColor = "red"
				data.BlockedBadge = true
			} else if pct >= policy.WarnThresholdPercent {
				data.BarColor = "orange"
				data.WarnBadge = true
			}
		}

		for _, a := range appTotals {
			row := subjectRow{
				Label:   a.Label,
				Minutes: a.Seconds / 60,
			}
			for _, l := range policy.Limits {
				if l.Kind == domain.KindApp && domain.MatchesDomain(l.Subject, a.Subject) {
					limitSec := l.DailyLimitMinutes * 60
					if a.Seconds >= limitSec {
						row.Blocked = true
					} else if a.Seconds >= limitSec*policy.WarnThresholdPercent/100 {
						row.Warn = true
					}
					break
				}
			}
			data.Apps = append(data.Apps, row)
		}
		if data.Apps == nil {
			data.Apps = []subjectRow{}
		}

		for _, w2 := range webTotals {
			row := subjectRow{
				Label:   w2.Label,
				Minutes: w2.Seconds / 60,
			}
			for _, l := range policy.Limits {
				if l.Kind == domain.KindWeb && domain.MatchesDomain(l.Subject, w2.Subject) {
					limitSec := l.DailyLimitMinutes * 60
					if w2.Seconds >= limitSec {
						row.Blocked = true
					} else if w2.Seconds >= limitSec*policy.WarnThresholdPercent/100 {
						row.Warn = true
					}
					break
				}
			}
			data.Websites = append(data.Websites, row)
		}
		if data.Websites == nil {
			data.Websites = []subjectRow{}
		}

		if err := renderPage(w, "device.gohtml", data); err != nil {
			log.Printf("render device detail: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
		}
	}
}

func htmlEsc(s string) string {
	return template.HTMLEscapeString(s)
}

// formatAge formats a duration as a human-readable age string
// using the largest unit (minutes, hours, or days). Floor is used
// so the age badge does not round up prematurely.
func formatAge(d time.Duration) string {
	min := int(math.Floor(d.Minutes()))
	if min < 60 {
		return fmt.Sprintf("%d min", min)
	}
	hrs := min / 60
	if hrs < 24 {
		return fmt.Sprintf("%d h", hrs)
	}
	days := hrs / 24
	return fmt.Sprintf("%d d", days)
}
