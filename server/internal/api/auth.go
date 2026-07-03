package api

import (
	"context"
	"net/http"
	"strings"

	"pcontrol/server/internal/store"
)

type contextKey string

const deviceIDKey contextKey = "device_id"

// DeviceIDFromContext extracts the authenticated device ID from the request context.
func DeviceIDFromContext(ctx context.Context) (int64, bool) {
	id, ok := ctx.Value(deviceIDKey).(int64)
	return id, ok
}

// AuthMiddleware returns middleware that validates the Bearer token and
// sets the device ID in the request context.
func AuthMiddleware(s *store.Store) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			auth := r.Header.Get("Authorization")
			if !strings.HasPrefix(auth, "Bearer ") {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			token := strings.TrimPrefix(auth, "Bearer ")

			dev, err := s.DeviceByToken(token)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}

			ctx := context.WithValue(r.Context(), deviceIDKey, dev.ID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}
