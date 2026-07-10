# pcontrol — Future improvements plan

This plan is written to be executed by coding agents. **Read the whole
document before starting any stage.** File paths, names, schemas, and
semantics below are normative. When something is ambiguous, prefer the
simplest solution consistent with this document and with the existing code.

## 0. How to use this plan (instructions for agents)

1. Work **one stage at a time, in order**. Do not start a stage until the
   previous stage's Success Criteria all pass.
2. **TDD is mandatory**: for every task, write the failing test first (red),
   write minimal code to pass (green), then refactor. Never disable or skip
   a test to make a stage "pass".
3. Update the `**Status**` line of each stage as you go
   (`Not Started` → `In Progress` → `Complete`).
4. Commit after each green task with a message like
   `server/store: persist device battery status (Stage 1.2)`.
5. Verification commands (run all before marking any stage complete):

   ```sh
   cd server && go test ./... && go vet ./...
   cd android && gradle :core:test :app:testDebugUnitTest
   ```

6. Backward compatibility is a hard requirement throughout: an **old
   Android client must keep working against a new server, and a new client
   against an old server**. All new JSON fields are optional; the Kotlin
   `Json` decoder already sets `ignoreUnknownKeys = true` and Go's
   `encoding/json` ignores unknown fields by default. Never make a new
   field required.

## 1. Architecture recap (read before coding)

- `server/` — Go module `pcontrol/server`.
  - `internal/store/` — SQLite via `modernc.org/sqlite`. Schema lives in
    `migrations.sql`, which is **executed in full on every startup** by
    `store.Open()` (`store.go`). Everything in it must be idempotent
    (`CREATE TABLE IF NOT EXISTS`, `INSERT OR IGNORE`).
  - `internal/domain/types.go` — plain structs (`Device`, `Event`,
    `Policy`, …). No behavior beyond `aggregate.go` helpers.
  - `internal/api/sync.go` — `POST /api/v1/sync`, bearer-token
    authenticated per device (see `api/auth.go`). Request/response JSON
    structs are private (`syncRequest`, `syncResponse`).
  - `internal/web/` — admin dashboard. Handlers in `dashboard.go` /
    `limits.go`, view-model structs in `templatesData.go`, Go templates in
    `templates/*.gohtml` (embedded via `go:embed` in `render.go`).
- `android/app/` — Kotlin app.
  - `TrackerService.kt` — foreground service; ticks every 10 s, calls
    `onSync()` every 60 s.
  - `SyncClient.kt` — kotlinx-serialization DTOs (`SyncRequest`,
    `SyncEvent`, `SyncResponse`, …) + OkHttp POST. DTO field names use
    `@SerialName("snake_case")`.
- Existing tests to imitate: `server/internal/api/sync_test.go`,
  `server/internal/store/devices_test.go`,
  `server/internal/web/dashboard_test.go`,
  `android/app/src/test/kotlin/com/pcontrol/app/SyncClientTest.kt`.

---

## Stage 1: Battery status of monitored devices

**Goal**: The parent dashboard shows, for every device, the current battery
percentage and whether the device is charging, updated on every sync
(≈ every 60 s while the tracker runs).

**Success Criteria**:

- The Android client includes battery percent + charging state in every
  sync request, even when there are no usage events to upload.
- The server persists the latest battery reading per device.
- The dashboard (`/`) and device detail page (`/devices/{id}`) show the
  battery state next to "last seen", colored red below 20 %.
- Old clients (no battery fields) still sync successfully; the dashboard
  shows nothing for them instead of a bogus value.

**Status**: Complete

### 1.1 Server: schema migration (version 2)

`migrations.sql` cannot hold `ALTER TABLE` statements because it is re-run
on every startup and SQLite errors on a duplicate column. Introduce a
minimal versioned-migration step in Go instead:

1. In `server/internal/store/store.go`, after the existing
   `db.Exec(string(migrations))` call in `Open()`, call a new unexported
   function `migrateV2(db *sql.DB) error`.
2. Implement `migrateV2` in `store.go`:

   ```go
   // migrateV2 adds battery-status columns to devices (idempotent).
   func migrateV2(db *sql.DB) error {
       var n int
       err := db.QueryRow(
           `SELECT COUNT(*) FROM pragma_table_info('devices')
            WHERE name = 'battery_percent'`).Scan(&n)
       if err != nil {
           return fmt.Errorf("check battery column: %w", err)
       }
       if n > 0 {
           return nil // already applied
       }
       stmts := []string{
           `ALTER TABLE devices ADD COLUMN battery_percent INTEGER`,
           `ALTER TABLE devices ADD COLUMN battery_charging INTEGER`,
           `ALTER TABLE devices ADD COLUMN battery_updated_at TEXT`,
       }
       for _, stmt := range stmts {
           if _, err := db.Exec(stmt); err != nil {
               return fmt.Errorf("apply v2 migration: %w", err)
           }
       }
       _, err = db.Exec(
           `INSERT OR IGNORE INTO schema_migrations (version) VALUES (2)`)
       return err
   }
   ```

3. Column semantics: all three columns are `NULL` until the first sync
   that carries battery data. `battery_percent` is 0–100.
   `battery_charging` is 0/1. `battery_updated_at` is RFC 3339 UTC, same
   format as `last_seen_at`.

**Test first** (`store/devices_test.go` or a new `store/store_test.go`):
open a store, close it, open the same file again — no error (proves
idempotency); `pragma_table_info('devices')` contains the three new
columns.

### 1.2 Server: domain + store

1. In `server/internal/domain/types.go`, extend `Device`:

   ```go
   type Device struct {
       ID               int64
       Name             string
       TokenHash        string     // hex(sha256(raw token))
       CreatedAt        time.Time
       LastSeenAt       *time.Time // nil until first sync
       BatteryPercent   *int       // nil until first battery report, 0–100
       BatteryCharging  *bool      // nil until first battery report
       BatteryUpdatedAt *time.Time // nil until first battery report
   }
   ```

2. In `server/internal/store/devices.go`:
   - Add `UpdateBatteryStatus(deviceID int64, percent int, charging bool, t time.Time) error`
     running
     `UPDATE devices SET battery_percent = ?, battery_charging = ?, battery_updated_at = ? WHERE id = ?`
     (store `charging` as 0/1, `t` as `t.UTC().Format(time.RFC3339)`).
   - Extend both `SELECT` statements (`deviceByHash`, `deviceByID`) and
     `scanDevice` to read the three new columns using `sql.NullInt64` /
     `sql.NullString`, mapping invalid (NULL) to nil pointers — follow the
     existing `last_seen_at` handling in `scanDevice` exactly.

**Tests first** (`store/devices_test.go`): (a) freshly created device has
nil battery fields; (b) after `UpdateBatteryStatus(id, 55, true, t)`,
`DeviceByTokenFromID` returns `*BatteryPercent == 55`,
`*BatteryCharging == true`, and `BatteryUpdatedAt` matching `t` truncated
to seconds.

### 1.3 Server: accept battery in `POST /api/v1/sync`

In `server/internal/api/sync.go`:

1. Extend `syncRequest` with optional fields (pointers so absence is
   detectable):

   ```go
   type syncRequest struct {
       DeviceTime      string      `json:"device_time"`
       PolicyVersion   int         `json:"policy_version"`
       Events          []syncEvent `json:"events"`
       BatteryPercent  *int        `json:"battery_percent"`
       BatteryCharging *bool       `json:"battery_charging"`
   }
   ```

2. Validation, placed after JSON decoding and before event parsing, in the
   same fail-fast style as the existing field checks: if
   `req.BatteryPercent != nil` and the value is outside 0–100, respond
   `400 bad request`.
3. Persistence, placed right after the existing `TouchLastSeen` call: if
   `req.BatteryPercent != nil`, call
   `s.UpdateBatteryStatus(deviceID, *req.BatteryPercent, charging, time.Now())`
   where `charging` is `*req.BatteryCharging` if set, else `false`. Log
   and continue on error (same pattern as `TouchLastSeen` — battery is
   best-effort and must never fail the usage upload). The response body is
   unchanged.

**Tests first** (`api/sync_test.go`, copy the setup of an existing sync
test): (a) sync with `"battery_percent": 42, "battery_charging": true`
returns 200 and the device row has those values; (b) sync **without**
battery fields returns 200 and leaves existing battery values untouched;
(c) `"battery_percent": 101` and `-1` both return 400; (d) a sync with an
empty `events` array and only battery fields returns 200 (this behavior
already works — `InsertEvents` is skipped when the slice is empty — but
lock it in with a test because Stage 1.5 depends on it).

### 1.4 Server: show battery on dashboard and device detail

1. In `server/internal/web/templatesData.go` add to **both**
   `dashboardDeviceEntry` and `deviceDetailData`:

   ```go
   HasBattery      bool
   BatteryPercent  int
   BatteryCharging bool
   BatteryLow      bool // true below 20 percent
   ```

2. In `server/internal/web/dashboard.go`:
   - `dashboard()`: extend the device list query to also select
     `battery_percent` and `battery_charging` (as `sql.NullInt64`), and
     populate the new view-model fields (`HasBattery` = percent column is
     non-NULL; `BatteryLow` = percent < 20).
   - `deviceDetail()`: it already loads the device via
     `DeviceByTokenFromID`, which after Stage 1.2 carries the battery
     fields — copy them into `deviceDetailData` the same way.
3. Templates:
   - `templates/dashboard.gohtml`, inside the card next to the existing
     `last seen` span:

     ```gohtml
     {{if .HasBattery}}
       <span class="battery{{if .BatteryLow}} battery-low{{end}}">
         🔋 {{.BatteryPercent}}%{{if .BatteryCharging}} ⚡{{end}}
       </span>
     {{end}}
     ```

   - `templates/device.gohtml`: same snippet near the page header.
   - `templates/layout.gohtml`: add `.battery-low{color:#c00;font-weight:bold}`
     to the existing inline stylesheet, matching its current style.

**Tests first** (`web/dashboard_test.go`, follow the existing key-string
assertion pattern): (a) device with battery 15 % charging → dashboard body
contains `15%`, the charging marker, and `battery-low`; (b) device with
NULL battery → body does **not** contain `🔋`.

### 1.5 Android: read battery and send it on every sync

1. New file `android/app/src/main/kotlin/com/pcontrol/app/BatteryStatusReader.kt`
   — an adapter in the style of `UsageStatsAdapter.kt` so the logic stays
   testable:

   ```kotlin
   data class BatteryStatus(val percent: Int, val charging: Boolean)

   /** Reads current battery state from the sticky ACTION_BATTERY_CHANGED
    *  broadcast. Requires no permissions. Returns null if unavailable. */
   class BatteryStatusReader(private val context: Context) {
       fun read(): BatteryStatus? {
           val intent = context.registerReceiver(
               null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
           val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
           val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
           if (level < 0 || scale <= 0) return null
           val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
           val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
           return BatteryStatus(level * 100 / scale, charging)
       }
   }
   ```

2. In `SyncClient.kt`, extend `SyncRequest` with optional fields (defaults
   keep old servers happy — Go ignores unknown JSON keys anyway):

   ```kotlin
   @kotlinx.serialization.SerialName("battery_percent")
   val batteryPercent: Int? = null,
   @kotlinx.serialization.SerialName("battery_charging")
   val batteryCharging: Boolean? = null
   ```

   Note: kotlinx-serialization omits null defaults only when
   `encodeDefaults` is false, which is the library default for the plain
   `Json { ... }` builder used here — verify in the test below that null
   battery fields are absent from the encoded JSON.

3. In `TrackerService.kt`, `onSync()`:
   - **Remove the early return** `if (unsynced.isEmpty()) return`. It
     currently prevents any sync (and therefore any battery/last-seen
     update) while the phone is idle. After removal, `events` is simply an
     empty list; the server accepts that (locked in by test 1.3-d).
   - Keep the existing prefs check (`serverUrl.isEmpty() || deviceToken.isEmpty()`)
     as the only early return.
   - Read `BatteryStatusReader(this).read()` and pass
     `batteryPercent = status?.percent`,
     `batteryCharging = status?.charging` into the `SyncRequest`.
   - The mark-synced loop already iterates `unsynced`, which is empty in
     the idle case — no further change needed.

**Tests first**:

- `SyncClientTest.kt` (follow existing serialization tests): (a) a
  `SyncRequest` with `batteryPercent = 77, batteryCharging = false`
  encodes to JSON containing `"battery_percent":77` and
  `"battery_charging":false`; (b) a request built without battery args
  encodes to JSON containing neither key.
- `BatteryStatusReader` depends on a sticky broadcast, so unit-testing it
  on the JVM is not practical; keep it thin (no logic besides the arith
  above) and do not add Robolectric just for this.

### 1.6 Manual verification (document result in the commit message)

```sh
# Server: register a device via the web UI, then simulate a client:
curl -s -X POST http://127.0.0.1:8080/api/v1/sync \
  -H "Authorization: Bearer <raw-token>" \
  -H "Content-Type: application/json" \
  -d '{"device_time":"2026-07-06T00:00:00Z","policy_version":0,
       "events":[],"battery_percent":18,"battery_charging":false}'
# Dashboard should now show "🔋 18%" in red for that device.
```

---

## Stage 2: Device online/offline indicator

**Goal**: The dashboard makes it obvious when a device has stopped syncing
(tracker killed, phone off, network gone) instead of showing a raw
timestamp the parent must interpret.

**Success Criteria**: Each dashboard card shows a green "online" badge when
`last_seen_at` is within the last 5 minutes, otherwise a gray "offline"
badge plus a human-readable age ("offline · 3 h").

**Status**: Complete

**Implementation notes**:

- Server-side only; no protocol or client change. The sync interval is
  60 s, so 5 minutes (allowing a few missed syncs) is the threshold.
  Define `const onlineThreshold = 5 * time.Minute` in `web/dashboard.go`.
- In `dashboard()`, parse `last_seen_at` (it is RFC 3339 or the literal
  `'never'` from the existing `COALESCE`) and compute
  `Online bool` and `LastSeenAge string` view-model fields on
  `dashboardDeviceEntry`. Format ages as `"<n> min"`, `"<n> h"`,
  `"<n> d"` (largest unit only; keep it a small pure helper function so it
  gets its own unit test).
- Template: replace the raw `(last seen: {{.LastSeenAt}})` span with the
  badge + age. Keep the exact timestamp in a `title=` attribute for
  hover.

**Tests**: unit test for the age-formatting helper (minutes/hours/days
boundaries); dashboard handler tests asserting `online` badge for a
just-touched device and `offline` for a device with `last_seen_at` set to
an hour ago (insert directly via `store.DB.Exec` in the test, as existing
tests do).

---

## Stage 3: Device management (rename, delete) in the web UI

**Goal**: The parent can rename a device (kids change phones) and delete a
device (and all its data) without touching sqlite3 by hand.

**Success Criteria**: `/devices/{id}` offers a rename form and a delete
button (with confirmation); after delete, the device disappears from the
dashboard and its events/limits/exclusions/settings rows are gone.

**Status**: Complete

**Implementation notes**:

- Store (`store/devices.go`):
  - `RenameDevice(id int64, name string) error` — reject empty name.
  - `DeleteDevice(id int64) error` — single transaction deleting from
    `usage_events`, `limits`, `exclusions`, `device_settings`, then
    `devices` (the schema declares `REFERENCES` but SQLite does not
    enforce it without `PRAGMA foreign_keys`, and no `ON DELETE CASCADE`
    is declared — delete children explicitly).
- Web (`web/dashboard.go` + `templates/device.gohtml`):
  - `POST /devices/{id}/rename` with a `name` form field → redirect back
    to `/devices/{id}`.
  - `POST /devices/{id}/delete` → redirect to `/`. Guard with a plain
    HTML `onsubmit="return confirm('Delete device and all its data?')"`.
  - Register the routes in `web/router.go` next to the existing device
    routes, behind the same auth middleware.
- Deleting a device instantly invalidates its bearer token (the row is
  gone, `DeviceByToken` fails, the client gets 401 and keeps counting
  locally). That is acceptable; do not build a soft-delete.

**Tests**: store tests for rename (including empty-name rejection) and for
delete removing all child rows; handler tests for both endpoints
(authenticated POST → 3xx redirect, effect visible via the store).

---

## Stage 4: 7-day usage history on the device page

**Goal**: The device detail page shows the past 7 days of total usage so
the parent can spot trends, not just today's number.

**Success Criteria**: `/devices/{id}` renders a small table (or CSS bar
list) of the last 7 day-keys with total counted minutes per day, using the
same exclusion rules as the daily total.

**Status**: Complete

**Implementation notes**:

- Store (`store/events.go`): add
  `DailyTotals(deviceID int64, fromDay, toDay string) (map[string]int, error)`
  returning counted seconds per `day` via
  `SELECT day, kind, subject, SUM(duration_seconds) ... GROUP BY day, kind, subject`
  then applying `domain.CountedTotalSeconds` per day (reuse the existing
  aggregation helpers — do not duplicate exclusion logic in SQL).
- Web: in `deviceDetail()`, compute the 7 day-keys ending at the `day`
  being viewed (string date math via `time.Parse("2006-01-02", day)` and
  `AddDate(0, 0, -i)`), fetch totals, add a `History []historyRow`
  (`Day string; Minutes int; BarPercent int`) field to
  `deviceDetailData`, and render it in `device.gohtml`. Scale
  `BarPercent` relative to the max day in the window; guard against
  division by zero when all days are 0.
- Days with no events must still appear with 0 minutes.

**Tests**: store test seeding events across 3 days (one excluded subject)
and asserting per-day counted totals; handler test asserting all 7 day
labels appear and a zero day renders as `0 min`.

---

## Backlog (not staged — needs a design pass before implementation)

These are recorded so they are not forgotten; **do not implement them from
this document alone**. Each needs its own plan file first.

- **Bedtime / schedule windows**: block everything outside allowed hours.
  Touches the policy schema, sync protocol, `:core` `PolicyEngine`, and
  enforcement — the largest remaining feature; explicitly a v1 non-goal.
- **Parent alerts**: notify (email or push) when a device goes offline for
  more than N hours or battery drops below a threshold — natural follow-up
  to Stages 1–2, but requires an outbound delivery channel the server does
  not have today.
- **Per-day-of-week limits / bonus time**: weekend vs school-day budgets.
- **Tamper evidence**: surface on the dashboard when usage access or the
  accessibility service has been revoked on the device (requires the
  client to self-report capability state in the sync request).
