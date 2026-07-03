# pcontrol — Remaining plan deviations

This is a handoff list for another agent. The project (a parental-control
system: Go server + Kotlin Android client) was implemented across 6 stages
per `docs/plans/IMPLEMENTATION_PLAN.md`. A review pass found and fixed 6
correctness bugs (commit `0abc1b6`); the items below are the **remaining
deviations from the plan** that were left unfixed because they are either
judgment calls or sizable rewrites. None are blocking; the test suites are
green.

## Verification commands

```sh
nix develop --command bash -c 'cd server && go test -count=1 ./internal/... && go vet ./...'
nix develop --command bash -c 'cd android && gradle clean :core:test :app:testDebugUnitTest'
```

---

## 1. Web UI: replace inline `fmt.Fprintf` with `html/template` + HTMX + `go:embed`

**Plan ref:** §8 ("Server-rendered Go templates + HTMX (vendor `htmx.min.js`
into the binary via `go:embed`; no npm, no build step)") and Stage 3 task 6
("Embed templates + htmx via `go:embed`").

**Current state:** the entire parent web UI is rendered with `fmt.Fprintf`
inside `server/internal/web/auth.go` (handlers `dashboard()`,
`deviceNewForm()`, `deviceNew()`, `deviceDetail()`, `limitsPage()`, plus the
`addLimit`/`deleteLimit`/`addExclusion`/`deleteExclusion`/`updateSettings`
POST handlers). There are no `*.gohtml` files, no `templates/` directory, and
no HTMX.

**Plan's expected layout** (§4 "Repository layout"):
```
server/internal/web/
├── router.go
├── auth.go           auth_test.go
├── dashboard.go      dashboard_test.go     ← MISSING
├── limits.go         limits_test.go         ← MISSING
└── templates/                              ← MISSING
    ├── layout.gohtml
    ├── login.gohtml
    ├── dashboard.gohtml
    ├── device.gohtml
    └── limits.gohtml
```

**What to do:**
1. Create `server/internal/web/templates/*.gohtml` for layout, login,
   dashboard, device, limits. Migrate the inline HTML from `auth.go`.
2. Embed the templates directory with `//go:embed templates/*` (see
   `server/internal/store/store.go` for the existing `go:embed` pattern used
   for `migrations.sql`).
3. Vendor `htmx.min.js` into the binary via `go:embed` and serve it; convert
   the add-limit / add-exclusion POSTs to HTMX row-append (§8 route table
   notes "HTMX row append" for `POST /devices/{id}/limits`).
4. Split handlers into `dashboard.go` and `limits.go` to match the layout.
5. Add `dashboard_test.go` / `limits_test.go` per Stage 3 tasks 4–5: "tests
   assert key strings in rendered HTML (subject labels, minute counts, limit
   values)" and "add limit → row appears in `GetPolicy` and in the page;
   delete removes it; total limit set/clear".

**Note:** This is the largest item. The inline HTML works today, so this is a
structural-compliance rewrite, not a bug fix. If you skip everything else,
still do the missing tests (Stage 3 tasks 4–5) since the plan mandates them.

---

## 2. Dashboard `/` missing the planned overview

**Plan ref:** §8 route table — `GET /` purpose: "Dashboard: per device,
today's total vs limit, top apps and sites (label, minutes, limit, progress
bar)".

**Current state:** `webAuthHandler.dashboard()` in `auth.go` lists devices
with name + `last_seen_at` only. The device detail page (`/devices/{id}`)
*does* render apps/sites tables with BLOCKED/WARN badges and a total-usage
progress bar (added in Stage 5 task 5), but the root dashboard overview is
missing the per-device total-vs-limit and top-apps/sites summary.

**What to do:** In `dashboard()`, for each device, query
`store.UsageTotals(deviceID, today)` and `store.GetPolicy(deviceID)`, compute
`domain.CountedTotalSeconds(...)`, and render a per-device card showing
today's total vs total limit (progress bar) plus top 3–5 apps/sites by
minutes. Reuse the badge/progress-bar rendering already in `deviceDetail()`.

**Caveat:** the server has no notion of the device's local timezone, so
"today" on the server is `time.Now().UTC()` (see item 6 below). For the
overview, using UTC today is acceptable as a first cut; the device detail
page already does this.

---

## 3. Add `<datalist>` of seen subjects to the add-limit form

**Plan ref:** §8 "UI niceties (cheap, do them): subjects seen in usage data
appear in a `<datalist>` when adding a limit, so you pick
`com.google.android.youtube` from what was actually used instead of typing
it."

**Current state:** `limitsPage()` in `auth.go` renders:
```html
<input name="subject" placeholder="com.example.app or domain.com" required>
```
No `<datalist>`.

**What to do:** In `limitsPage()`, query
`store.UsageTotals(deviceID, day)` (or a new "all distinct subjects ever
seen" query if you want it across days) and emit a `<datalist id="subjects">`
with one `<option>` per subject, then add `list="subjects"` to the subject
input. Do the same for the add-exclusion form.

---

## 4. Use `EncryptedSharedPreferences` for the device token

**Plan ref:** §9 "server URL + device token" entry (stored in
`EncryptedSharedPreferences`)".

**Current state:** `MainActivity.kt` and `TrackerService.kt` store
`server_url` and `device_token` in plain `getSharedPreferences("pcontrol",
MODE_PRIVATE)`. The device token is a bearer secret that grants API access,
so storing it in plain prefs is a security downgrade.

**Files to change:**
- `android/app/src/main/kotlin/com/pcontrol/app/MainActivity.kt` —
  `isServerConfigured()` and `showServerConfigDialog()` (around the
  `getSharedPreferences("pcontrol", MODE_PRIVATE)` calls).
- `android/app/src/main/kotlin/com/pcontrol/app/TrackerService.kt` —
  `onSync()` reads `server_url` / `device_token` from the same prefs.

**What to do:**
1. Add the AndroidX Security dependency to `android/app/build.gradle.kts`:
   `implementation("androidx.security:security-crypto:1.1.0-alpha06")`
   (or the latest stable).
2. Create a small helper (e.g. `SecretPrefs.kt`) that wraps
   `EncryptedSharedPreferences.create(...)` and exposes
   `getServerUrl()` / `setServerUrl()` / `getDeviceToken()` /
   `setDeviceToken()`. Handle the case where the encrypted prefs master key
   isn't yet available.
3. Replace all `getSharedPreferences("pcontrol", MODE_PRIVATE)` reads/writes
   of `server_url` / `device_token` with the helper.

**Caveat:** `EncryptedSharedPreferences` requires API 23+; minSdk is 26, so
that's fine. Keep `policy_version` (an int, non-secret) in plain prefs or
move it too — your call, but it doesn't need encryption.

---

## 5. README is essentially empty

**Plan ref:** Stage 1 task 4 ("one-paragraph description, `nix develop`, how
to run tests for each half") and Stage 3 task 7 ("README section: VPS
install steps + note that the SQLite file is the only state to back up").

**Current state:** `README.md` is ~1 line.

**What to do:** Write a README covering:
- One-paragraph project description.
- Dev shell: `nix develop`.
- Server: `cd server && go test ./...`, `go run ./cmd/pcontrold --help`,
  run flags (`--db`, `--listen`, `--admin-password-hash` /
  `PCONTROL_ADMIN_HASH`), the `hash-password` subcommand.
- Android: `cd android && gradle :core:test :app:testDebugUnitTest`.
- VPS deploy: copy `deploy/pcontrold.service` (systemd, `DynamicUser=yes`,
  `StateDirectory=pcontrol`), `deploy/Caddyfile` reverse proxy to
  `127.0.0.1:8080`. Note: **the SQLite file (`pcontrol.db`) is the only
  state to back up.**
- Android SDK note: the flake provides the Android SDK via
  `androidenv.composeAndroidPackages`; if it times out, install via Android
  Studio.

---

## 6. Device-detail default day uses UTC, not device-local

**Plan ref:** §6 "A day is the device-local calendar date (`YYYY-MM-DD`). All
counters reset at local midnight. The client computes the day key; the
server trusts the `day` field on events."

**Current state:** `webAuthHandler.deviceDetail()` in `auth.go` defaults
`day` to `time.Now().UTC().Format("2006-01-02")`. Events are stored with
device-local day keys, so near local midnight the dashboard shows the wrong
"today" (UTC day ≠ device-local day).

**What to do:** The server doesn't know the device's timezone. The simplest
fix consistent with the plan ("the server trusts the `day` field on events")
is to default `day` to the most recent day key that actually has events for
that device:
```sql
SELECT day FROM usage_events WHERE device_id = ? ORDER BY day DESC LIMIT 1
```
falling back to UTC today only if there are no events yet. This makes the
picker land on the device's "today" by default.

---

## Priority recommendation

If you can't do all six, do them in this order:

1. **#5 README** — small, high value, plan-mandated.
2. **#4 EncryptedSharedPreferences** — security, small, isolated.
3. **#3 `<datalist>`** — small, isolated, real UX win.
4. **#6 device-local default day** — small correctness fix.
5. **#2 dashboard overview** — medium, visible to parents.
6. **#1 templates + htmx + tests** — largest; structural compliance. The
   inline HTML works, so this is lowest urgency, but the missing
   `dashboard_test.go` / `limits_test.go` (Stage 3 tasks 4–5) are
   plan-mandated and should be added even if you keep the inline rendering.
