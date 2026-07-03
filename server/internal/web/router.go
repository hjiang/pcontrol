package web

import (
	"net/http"

	"pcontrol/server/internal/api"
	"pcontrol/server/internal/store"
)

// NewRouter creates the HTTP mux with all server routes.
// adminHash is the bcrypt hash of the admin password.
func NewRouter(s *store.Store, adminHash string) http.Handler {
	mux := http.NewServeMux()

	// Health check (no auth)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	})

	// Device API — bearer token auth on its own mux to avoid pattern conflicts
	apiMux := http.NewServeMux()
	api.HandleSync(s, apiMux)
	mux.Handle("/api/v1/", api.AuthMiddleware(s)(apiMux))

	// Web auth — no session required
	wa := &webAuthHandler{store: s, adminHash: adminHash}
	mux.HandleFunc("GET /login", wa.handleLoginForm)
	mux.HandleFunc("POST /login", wa.handleLogin)

	// Web routes — session required
	mux.Handle("POST /logout", wa.requireSession(wa.handleLogout))

	// Instead of GET / (subtree which conflicts), use specific paths and a default handler
	mux.Handle("GET /{$}", wa.requireSession(wa.dashboard()))

	mux.Handle("GET /devices/new", wa.requireSession(wa.deviceNewForm()))
	mux.Handle("POST /devices/new", wa.requireSession(wa.deviceNew()))
	mux.Handle("GET /devices/{id}", wa.requireSession(wa.deviceDetail()))
	mux.Handle("GET /devices/{id}/limits", wa.requireSession(wa.limitsPage()))
	mux.Handle("POST /devices/{id}/limits", wa.requireSession(wa.addLimit()))
	mux.Handle("POST /devices/{id}/limits/{limitId}/delete", wa.requireSession(wa.deleteLimit()))
	mux.Handle("POST /devices/{id}/exclusions", wa.requireSession(wa.addExclusion()))
	mux.Handle("POST /devices/{id}/exclusions/{exclusionId}/delete", wa.requireSession(wa.deleteExclusion()))
	mux.Handle("POST /devices/{id}/settings", wa.requireSession(wa.updateSettings()))

	return mux
}
