package web

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"net/http"
	"time"

	"golang.org/x/crypto/bcrypt"
	"pcontrol/server/internal/domain"
	"pcontrol/server/internal/store"
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
	renderPage(w, "login.gohtml", loginData{})
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
		w.WriteHeader(http.StatusUnauthorized)
		renderPage(w, "login.gohtml", loginData{ErrorMsg: "Invalid password"})
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

	// Secure is set only when the request arrived over TLS. A hard-coded
	// Secure=true makes the session cookie invisible to browsers on plain
	// HTTP (e.g. a LAN-only Unraid deployment at http://unraid-ip:7285/),
	// which makes login impossible: the password is accepted, the session
	// is created, but the browser discards the cookie and the next request
	// is treated as unauthenticated.
	http.SetCookie(w, &http.Cookie{
		Name:     "session",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   r.TLS != nil,
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
		Secure:   r.TLS != nil,
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

// --- helpers ---

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
