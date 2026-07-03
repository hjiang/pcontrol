package web

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"net/http"
	"time"

	"pcontrol/server/internal/domain"
	"pcontrol/server/internal/store"
	"golang.org/x/crypto/bcrypt"
)

type webAuthHandler struct {
	store     *store.Store
	adminHash string
}

// bcryptHash returns the bcrypt hash of the given password.
func bcryptHash(password string) (string, error) {
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// handleLoginForm renders the login page.
func (h *webAuthHandler) handleLoginForm(w http.ResponseWriter, r *http.Request) {
	renderLogin(w, "")
}

// handleLogin processes a login form submission.
func (h *webAuthHandler) handleLogin(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	password := r.FormValue("password")

	// Compare password against stored hash
	if err := bcrypt.CompareHashAndPassword([]byte(h.adminHash), []byte(password)); err != nil {
		renderLogin(w, "Invalid password")
		return
	}

	// Create session
	token, err := generateSessionToken()
	if err != nil {
		log.Printf("session token error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	expires := time.Now().Add(30 * 24 * time.Hour) // 30 days
	if _, err := h.store.DB.Exec(
		`INSERT INTO sessions (token, expires_at) VALUES (?, ?)`,
		tokenHash(token), expires.UTC().Format(time.RFC3339),
	); err != nil {
		log.Printf("session insert error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	http.SetCookie(w, &http.Cookie{
		Name:     "session",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
		Expires:  expires,
	})
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

// handleLogout deletes the current session.
func (h *webAuthHandler) handleLogout(w http.ResponseWriter, r *http.Request) {
	cookie, err := r.Cookie("session")
	if err == nil {
		h.store.DB.Exec(`DELETE FROM sessions WHERE token = ?`, tokenHash(cookie.Value))
	}

	http.SetCookie(w, &http.Cookie{
		Name:     "session",
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		MaxAge:   -1,
	})
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// requireSession is middleware that checks for a valid session cookie.
func (h *webAuthHandler) requireSession(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie("session")
		if err != nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}

		var expiresAt string
		row := h.store.DB.QueryRow(
			`SELECT expires_at FROM sessions WHERE token = ?`,
			tokenHash(cookie.Value),
		)
		if err := row.Scan(&expiresAt); err != nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}

		expires, _ := time.Parse(time.RFC3339, expiresAt)
		if time.Now().After(expires) {
			h.store.DB.Exec(`DELETE FROM sessions WHERE token = ?`, tokenHash(cookie.Value))
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}

		next(w, r)
	})
}

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

		type deviceRow struct {
			ID         int64
			Name       string
			CreatedAt  string
			LastSeenAt string
		}
		var devices []deviceRow
		for rows.Next() {
			var d deviceRow
			if err := rows.Scan(&d.ID, &d.Name, &d.CreatedAt, &d.LastSeenAt); err != nil {
				continue
			}
			devices = append(devices, d)
		}

		// Simple HTML dashboard
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<html><body><h1>pcontrol</h1>`)
		fmt.Fprintf(w, `<a href="/devices/new">Register device</a> | <form action="/logout" method="post" style="display:inline"><button>Logout</button></form>`)
		fmt.Fprintf(w, `<h2>Devices</h2><ul>`)
		for _, d := range devices {
			fmt.Fprintf(w, `<li><a href="/devices/%d">%s</a> (last seen: %s)</li>`, d.ID, d.Name, d.LastSeenAt)
		}
		fmt.Fprintf(w, `</ul></body></html>`)
	}
}

func (h *webAuthHandler) deviceNewForm() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<html><body><h1>Register device</h1>`)
		fmt.Fprintf(w, `<form action="/devices/new" method="post">
			<label>Device name: <input name="name" required></label>
			<button>Register</button>
		</form>`)
		fmt.Fprintf(w, `<a href="/">Back</a></body></html>`)
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

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<html><body><h1>Device registered</h1>`)
		fmt.Fprintf(w, `<p>Device: %s</p>`, dev.Name)
		fmt.Fprintf(w, `<p><strong>Token (show this to the child once):</strong></p>`)
		fmt.Fprintf(w, `<pre style="background:#eee;padding:1em;font-size:1.2em">%s</pre>`, rawToken)
		fmt.Fprintf(w, `<p>This token will not be shown again.</p>`)
		fmt.Fprintf(w, `<a href="/devices/%d">View device</a> | <a href="/">Back</a>`, dev.ID)
		fmt.Fprintf(w, `</body></html>`)
	}
}

func (h *webAuthHandler) deviceDetail() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)

		day := r.URL.Query().Get("day")
		if day == "" {
			day = time.Now().UTC().Format("2006-01-02")
		}

		policy, err := h.store.GetPolicy(deviceID)
		if err != nil {
			policy = domain.Policy{}
		}

		appTotals, webTotals, _ := h.store.UsageTotals(deviceID, day)

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<html><body><h1>Device %s</h1>`, id)
		fmt.Fprintf(w, `<a href="/devices/%s/limits">Limits</a> | <a href="/">Back</a><hr>`, id)

		// Day picker
		fmt.Fprintf(w, `<form method="get"><label>Day: <input name="day" value="%s"></label><button>View</button></form>`, day)

		// Total usage bar
		totalSeconds := domain.CountedTotalSeconds(appTotals, webTotals, policy.Exclusions)
		totalMinutes := totalSeconds / 60
		fmt.Fprintf(w, `<h2>Total usage: %dm</h2>`, totalMinutes)
		if policy.TotalDailyLimitMin != nil {
			limitMin := *policy.TotalDailyLimitMin
			limitMinInt := int(limitMin)
			pct := 0
			if limitMinInt > 0 {
				pct = int(totalMinutes) * 100 / limitMinInt
			}
			barColor := "green"
			badge := ""
			if int(totalMinutes) >= limitMinInt {
				barColor = "red"
				badge = ` <strong style="color:red">[BLOCKED]</strong>`
			} else if pct >= policy.WarnThresholdPercent {
				barColor = "orange"
				badge = ` <strong style="color:orange">[WARN]</strong>`
			}
			fmt.Fprintf(w, `<div style="background:#eee;height:20px;width:300px;border-radius:4px">`)
			fmt.Fprintf(w, `<div style="background:%s;height:20px;width:%d%%;border-radius:4px"></div>`, barColor, min(pct, 100))
			fmt.Fprintf(w, `</div>%s`, badge)
			fmt.Fprintf(w, `<p>Limit: %dm (warn at %d%%)</p>`, limitMinInt, policy.WarnThresholdPercent)
		} else {
			fmt.Fprintf(w, `<p>No total limit set</p>`)
		}

		// App usage table
		fmt.Fprintf(w, `<h2>Apps</h2><table border="1"><tr><th>App</th><th>Minutes</th><th>Status</th></tr>`)
		for _, a := range appTotals {
			status := ""
			for _, l := range policy.Limits {
				if l.Kind == domain.KindApp && domain.MatchesDomain(l.Subject, a.Subject) {
					limitSec := l.DailyLimitMinutes * 60
					if a.Seconds >= limitSec {
						status = `<strong style="color:red">BLOCKED</strong>`
					} else if a.Seconds >= limitSec*policy.WarnThresholdPercent/100 {
						status = `<strong style="color:orange">WARN</strong>`
					}
					break
				}
			}
			fmt.Fprintf(w, `<tr><td>%s</td><td>%d</td><td>%s</td></tr>`, a.Label, a.Seconds/60, status)
		}
		fmt.Fprintf(w, `</table>`)

		// Web usage table
		fmt.Fprintf(w, `<h2>Websites</h2><table border="1"><tr><th>Domain</th><th>Minutes</th><th>Status</th></tr>`)
		for _, w2 := range webTotals {
			status := ""
			for _, l := range policy.Limits {
				if l.Kind == domain.KindWeb && domain.MatchesDomain(l.Subject, w2.Subject) {
					limitSec := l.DailyLimitMinutes * 60
					if w2.Seconds >= limitSec {
						status = `<strong style="color:red">BLOCKED</strong>`
					} else if w2.Seconds >= limitSec*policy.WarnThresholdPercent/100 {
						status = `<strong style="color:orange">WARN</strong>`
					}
					break
				}
			}
			fmt.Fprintf(w, `<tr><td>%s</td><td>%d</td><td>%s</td></tr>`, w2.Label, w2.Seconds/60, status)
		}
		fmt.Fprintf(w, `</table>`)

		fmt.Fprintf(w, `<a href="/devices/%s/limits">Manage limits</a>`, id)
		fmt.Fprintf(w, `</body></html>`)
	}
}

func (h *webAuthHandler) limitsPage() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		deviceID := parseID(id)

		policy, err := h.store.GetPolicy(deviceID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<html><body><h1>Limits for device %s</h1>`, id)

		// Total limit
		total := "none"
		if policy.TotalDailyLimitMin != nil {
			total = fmt.Sprintf("%d minutes", *policy.TotalDailyLimitMin)
		}
		fmt.Fprintf(w, `<p>Total daily limit: %s (warn at %d%%)</p>`, total, policy.WarnThresholdPercent)

		// Limits table
		fmt.Fprintf(w, `<h2>Limits</h2><table border="1"><tr><th>Kind</th><th>Subject</th><th>Minutes/day</th><th></th></tr>`)
		for _, l := range policy.Limits {
			fmt.Fprintf(w, `<tr><td>%s</td><td>%s</td><td>%d</td>
				<td><form action="/devices/%d/limits/%d/delete" method="post"><button>Delete</button></form></td></tr>`,
				l.Kind, l.Subject, l.DailyLimitMinutes, deviceID, l.ID)
		}
		fmt.Fprintf(w, `</table>`)

		// Add limit form
		fmt.Fprintf(w, `<h3>Add limit</h3>
			<form action="/devices/%d/limits" method="post">
				<select name="kind"><option value="app">App</option><option value="web">Website</option></select>
				<input name="subject" placeholder="com.example.app or domain.com" required>
				<input name="minutes" type="number" min="1" placeholder="Minutes" required>
				<button>Add</button>
			</form>`, deviceID)

		// Exclusions table
		fmt.Fprintf(w, `<h2>Exclusions</h2><p style="color:#555">Web exclusions: always allowed — usable even after the daily limit.</p><table border="1"><tr><th>Kind</th><th>Subject</th><th></th></tr>`)
		for _, e := range policy.Exclusions {
			fmt.Fprintf(w, `<tr><td>%s</td><td>%s</td>
				<td><form action="/devices/%d/exclusions/%d/delete" method="post"><button>Delete</button></form></td></tr>`,
				e.Kind, e.Subject, deviceID, e.ID)
		}
		fmt.Fprintf(w, `</table>`)

		// Add exclusion form
		fmt.Fprintf(w, `<h3>Add exclusion</h3>
			<form action="/devices/%d/exclusions" method="post">
				<select name="kind"><option value="app">App</option><option value="web">Website</option></select>
				<input name="subject" placeholder="com.example.app or domain.com" required>
				<button>Add</button>
			</form>`, deviceID)

		// Settings form
		fmt.Fprintf(w, `<h2>Settings</h2>
			<form action="/devices/%d/settings" method="post">
				<label>Total daily limit (minutes, leave empty for none): <input name="total" type="number" min="1"></label><br>
				<label>Warn at percent: <input name="warn" type="number" min="1" max="100" value="%d"></label><br>
				<button>Save</button>
			</form>`, deviceID, policy.WarnThresholdPercent)

		fmt.Fprintf(w, `<a href="/devices/%s">Back to device</a>`, id)
		fmt.Fprintf(w, `</body></html>`)
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

		if _, err := h.store.SetLimit(deviceID, kindToDomain(kind), subject, minutes); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
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

		if _, err := h.store.AddExclusion(deviceID, kindToDomain(kind), subject); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
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
		http.Redirect(w, r, fmt.Sprintf("/devices/%d/limits", deviceID), http.StatusSeeOther)
	}
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

// --- helpers ---

func renderLogin(w http.ResponseWriter, errorMsg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if errorMsg != "" {
		w.WriteHeader(http.StatusUnauthorized)
	}
	fmt.Fprintf(w, `<html><body><h1>pcontrol Login</h1>`)
	if errorMsg != "" {
		fmt.Fprintf(w, `<p style="color:red">%s</p>`, errorMsg)
	}
	fmt.Fprintf(w, `<form action="/login" method="post">
		<label>Password: <input type="password" name="password" required></label>
		<button>Login</button>
	</form></body></html>`)
}

func generateSessionToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func tokenHash(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:])
}

func parseID(s string) int64 {
	var id int64
	fmt.Sscanf(s, "%d", &id)
	return id
}

func parseInt(s string) int {
	var v int
	fmt.Sscanf(s, "%d", &v)
	return v
}

func kindToDomain(k string) domain.Kind {
	if k == "app" {
		return domain.KindApp
	}
	return domain.KindWeb
}
