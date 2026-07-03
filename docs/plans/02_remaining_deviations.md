# pcontrol — Remaining plan deviations (resolved)

All six items from the original handoff list have been implemented. See
commit history for the full diff.

---

## ✅ 1. Web UI: replace inline `fmt.Fprintf` with `html/template` + HTMX + `go:embed`

**Completed:**

- Created `server/internal/web/templates/*.gohtml` for layout, login,
  dashboard, device, and limits. Each page is a standalone Go template
  rendered by `renderPage()`.
- Templates are embedded via `//go:embed templates/*.gohtml` in
  `render.go`.
- Vendor `htmx.min.js` embedded via `//go:embed static/*` in `render.go`
  and served at `/static/htmx.min.js`. The layout template includes the
  HTMX script tag.
- Add-limit / add-exclusion forms use `hx-post` / `hx-target` /
  `hx-swap="beforeend"` for row append without full page reload. Delete
  buttons use `hx-post` / `hx-target` / `hx-swap="outerHTML"` with
  `class="btn-link"` styling.
- Handlers split into `dashboard.go` (dashboard, deviceNewForm, deviceNew,
  deviceDetail) and `limits.go` (limitsPage, addLimit, deleteLimit,
  addExclusion, deleteExclusion, updateSettings) per the planned layout.
- Added `dashboard_test.go` (6 tests) and `limits_test.go` (6 tests) for
  key-string assertions, add/delete limit flow, add/delete exclusion flow,
  and settings mutations.
- `auth.go` retains login, logout, and session-mgmt handlers.

## ✅ 2. Dashboard `/` overview

**Completed:** `dashboard()` now queries `UsageTotals(deviceID, today)` and
`GetPolicy(deviceID)` for each device and renders a per-device card with:
today's total minutes vs limit (progress bar, green/orange/red), top 3
apps/sites by minutes, and a link to the device detail page.

## ✅ 3. Add `<datalist>` of seen subjects to the add-limit form

**Completed:**
- Added `DistinctSubjects(deviceID)` method to `store/events.go` that
  queries distinct (kind, subject, label) triples ordered by most-recent.
- `limitsPage()` queries subjects and emits `<datalist id="subjects">`
  with one `<option>` per subject.
- Both add-limit and add-exclusion subject inputs have `list="subjects"`.

## ✅ 4. Use `EncryptedSharedPreferences` for the device token

**Completed:**
- Added `androidx.security:security-crypto:1.0.0` to
  `android/app/build.gradle.kts`.
- Created `android/app/src/main/kotlin/.../SecretPrefs.kt` — a singleton
  wrapper around `EncryptedSharedPreferences` (AES-256 GCM via Android
  Keystore `MasterKey`), exposing `getServerUrl()`, `setServerUrl()`,
  `getDeviceToken()`, `setDeviceToken()`, and `isConfigured()`.
- `MainActivity.kt` and `TrackerService.kt` updated to read/write
  `server_url` / `device_token` through `SecretPrefs` instead of plain
  `getSharedPreferences("pcontrol", MODE_PRIVATE)`.
- Non-secret `policy_version` remains in plain prefs.

## ✅ 5. README

**Completed:** README covers:
- Project description and repository layout.
- Dev shell (`nix develop`).
- Server: `go test ./... && go vet ./...`, `go run ./cmd/pcontrold`,
  `--listen`, `--db`, `--admin-password-hash` / `PCONTROL_ADMIN_HASH`,
  `hash-password` subcommand.
- Android: `gradle :core:test`, `gradle :app:testDebugUnitTest`.
- VPS deploy: systemd unit, Caddyfile, SQLite backup note.
- Android SDK note.

## ✅ 6. Device-detail default day uses device-local timezone

**Completed:** `deviceDetail()` now defaults `day` to the most recent day
key that has events for the device (`SELECT day FROM usage_events WHERE
device_id = ? ORDER BY day DESC LIMIT 1`), falling back to UTC today only
if there are no events yet. This matches the plan's §6 principle that the
server trusts the `day` field on events.

## Summary

| # | Item | Status |
|---|------|--------|
| 1 | Templates + HTMX + tests | ✅ |
| 2 | Dashboard overview | ✅ |
| 3 | `<datalist>` | ✅ |
| 4 | `EncryptedSharedPreferences` | ✅ |
| 5 | README | ✅ |
| 6 | Device-local default day | ✅ |

All server tests pass (`go test -race ./internal/...`), and `go vet ./...`
is clean.
