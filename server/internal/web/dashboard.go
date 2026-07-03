package web

import (
	"fmt"
	"html/template"
	"log"
	"net/http"
	"time"

	"pcontrol/server/internal/domain"
)

func (h *webAuthHandler) dashboard() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}

		// List devices
		rows, err := h.store.DB.Query(
			`SELECT id, name, created_at, COALESCE(last_seen_at, 'never') FROM devices ORDER BY id`)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		type rawDevice struct {
			ID         int64
			Name       string
			CreatedAt  string
			LastSeenAt string
		}
		var rawDevices []rawDevice
		for rows.Next() {
			var d rawDevice
			if err := rows.Scan(&d.ID, &d.Name, &d.CreatedAt, &d.LastSeenAt); err != nil {
				continue
			}
			rawDevices = append(rawDevices, d)
		}
		rows.Close()

		today := time.Now().UTC().Format("2006-01-02")
		devices := make([]dashboardDeviceEntry, 0, len(rawDevices))

		for _, rd := range rawDevices {
			appTotals, webTotals, _ := h.store.UsageTotals(rd.ID, today)
			policy, _ := h.store.GetPolicy(rd.ID)

			totalSeconds := domain.CountedTotalSeconds(appTotals, webTotals, policy.Exclusions)
			totalMinutes := totalSeconds / 60

			entry := dashboardDeviceEntry{
				ID:           rd.ID,
				Name:         rd.Name,
				LastSeenAt:   rd.LastSeenAt,
				TotalMinutes: int(totalMinutes),
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
			if len(top) > 3 {
				top = top[:3]
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
		renderInline(w, `<h1>Register device</h1>
			<form action="/devices/new" method="post">
				<label>Device name: <input name="name" required></label>
				<button>Register</button>
			</form>
			<p><a href="/">Back</a></p>`)
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

		renderInline(w, fmt.Sprintf(`<h1>Device registered</h1>
			<p>Device: %s</p>
			<p><strong>Token (show this to the child once):</strong></p>
			<pre style="background:#eee;padding:1em;font-size:1.2em">%s</pre>
			<p>This token will not be shown again.</p>
			<p><a href="/devices/%d">View device</a> | <a href="/">Back</a></p>`,
			htmlEsc(dev.Name), htmlEsc(rawToken), dev.ID))
	}
}

func (h *webAuthHandler) deviceDetail() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)

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

		device, _ := h.store.DeviceByTokenFromID(deviceID)
		data := deviceDetailData{
			ID:           deviceID,
			Name:         device.Name,
			Day:          day,
			TotalMinutes: int(totalMinutes),
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

// renderInline renders a simple HTML page frame around already-escaped HTML
// content. The content must already be safe (HTML-escaped) before being
// passed here.
func renderInline(w http.ResponseWriter, innerHTML string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	ioWriteString(w, `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>pcontrol</title><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font-family:system-ui,-apple-system,sans-serif;line-height:1.5;color:#222;background:#f8f9fa;max-width:800px;margin:1rem auto;padding:0 1rem}a{color:#06c}h1{margin:0.5rem 0}pre{overflow-x:auto}</style>
</head><body>`)
	ioWriteString(w, innerHTML)
	ioWriteString(w, `</body></html>`)
}

func ioWriteString(w http.ResponseWriter, s string) {
	w.Write([]byte(s))
}

func htmlEsc(s string) string {
	return template.HTMLEscapeString(s)
}
