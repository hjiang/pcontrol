# pcontrol — Parental Control System: Implementation Plan

A self-hosted parental-control system for one Android phone. A Go server (with a
web UI) runs on a public VPS; a Kotlin Android client tracks app and website
usage, syncs it to the server, and enforces daily time limits locally.

This plan is written to be executed by coding agents. **Read the whole document
before starting any stage.** Follow it literally: file paths, names, schemas,
and semantics below are normative. When something is ambiguous, prefer the
simplest solution consistent with this document.

---

## 0. How to use this plan (instructions for agents)

1. Work **one stage at a time, in order**. Do not start a stage until the
   previous stage's Success Criteria all pass.
2. **TDD is mandatory**: for every task, write the failing test first (red),
   write minimal code to pass (green), then refactor. Never disable or skip a
   test to make a stage "pass".
3. Update the `**Status**` line of each stage as you go
   (`Not Started` → `In Progress` → `Complete`).
4. Commit after each green task with a message like
   `server/store: record usage events with dedupe (Stage 2.3)`.
5. Run the verification commands listed at the end of each stage before
   marking it complete.
6. Anything under **Non-goals (v1)** must NOT be implemented, even if it seems
   easy.

---

## 1. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Platform | Android only | Requirement |
| Repo layout | Monorepo: `server/` + `android/` | Requirement |
| Server stack | Go (stdlib `net/http`), `html/template` + HTMX, SQLite | Single binary, trivial deploy, easy TDD |
| SQLite driver | `modernc.org/sqlite` (pure Go, no cgo) | No C toolchain needed in CI/nix |
| Android client | Kotlin, min SDK 26 (Android 8.0), target SDK 34 | Modern APIs, one real device |
| Web tracking | `AccessibilityService` reading the browser URL bar | Per-site time + blocking, no TLS interception |
| App tracking | `UsageStatsManager` polled by a foreground service | Official API for foreground-app time |
| Enforcement | Warn (notification) at 90% of a limit, hard block (overlay / back-navigation) at 100% | "Warn, then block" |
| Connectivity | Server on a public VPS, HTTPS via Caddy reverse proxy, per-device bearer token | Always reachable |
| Dev environment | `flake.nix` dev shell (Go + JDK + Android SDK + sqlite) | Requirement |
| Users | One parent (admin password), one child device (extensible to more) | Keep it simple |

## 2. Non-goals (v1)

- iOS, multiple parents, user accounts/roles.
- Tamper-proofing (Device Owner mode, uninstall protection). Practical
  mitigation for now: the child's phone should not know the parent PIN and the
  child account should not be able to remove Device Admin apps. Revisit later.
- VPN/DNS-level traffic logging (in-app web traffic outside browsers is not
  tracked).
- Location tracking, message monitoring, screenshots, remote control.
- Schedules (bedtime windows), bonus time, per-day-of-week limits. Only flat
  daily limits.
- Push notifications to the parent. The web dashboard is pull-only.

---

## 3. Architecture overview

```
┌──────────────────────────── Android phone ────────────────────────────┐
│                                                                       │
│  TrackerService (foreground service, ticks every 10s)                 │
│    ├─ AppUsagePoller ── UsageStatsManager → current foreground app    │
│    ├─ BrowserAccessibilityService → current URL/domain in browser     │
│    ├─ UsageRecorder ── Room DB: per-(day, kind, subject) counters     │
│    ├─ PolicyEngine (:core) → ALLOW / WARN / BLOCK_APP / BLOCK_WEB     │
│    ├─ Enforcer ── WARN → notification; BLOCK → overlay or BACK action │
│    └─ SyncClient ── every 60s: POST /api/v1/sync                      │
│         • uploads usage-event deltas (idempotent via UUID)            │
│         • downloads latest policy (limits, exclusions, total limit)   │
└───────────────────────────────────────────────────────────────────────┘
                                   │ HTTPS (Caddy)  Bearer <device token>
                                   ▼
┌──────────────────────────── VPS server (Go) ──────────────────────────┐
│  /api/v1/sync           device-facing JSON API                        │
│  /login, /, /devices/…  parent web UI (html/template + HTMX)          │
│  SQLite file (pcontrol.db): devices, usage_events, limits,            │
│                             exclusions, device_settings, sessions     │
└───────────────────────────────────────────────────────────────────────┘
```

Key principle: **the phone enforces, the server decides.** The client caches
the last-synced policy and enforces it even when offline; usage events are
queued locally and uploaded when connectivity returns.

---

## 4. Repository layout (normative)

```
pcontrol/
├── flake.nix
├── .gitignore
├── README.md
├── IMPLEMENTATION_PLAN.md        # this file — delete when all stages Complete
├── server/                       # Go module: pcontrol/server
│   ├── go.mod
│   ├── cmd/pcontrold/main.go     # flag parsing, wiring, ListenAndServe
│   └── internal/
│       ├── domain/               # pure types + policy/aggregation logic
│       │   ├── types.go
│       │   ├── aggregate.go
│       │   └── aggregate_test.go
│       ├── store/                # SQLite persistence
│       │   ├── store.go          # Open, migrations
│       │   ├── devices.go        devices_test.go
│       │   ├── events.go         events_test.go
│       │   ├── policy.go         policy_test.go
│       │   └── migrations.sql    # embedded via go:embed
│       ├── api/                  # device-facing JSON API
│       │   ├── sync.go           sync_test.go
│       │   └── auth.go           auth_test.go
│       └── web/                  # parent web UI
│           ├── router.go
│           ├── auth.go           auth_test.go
│           ├── dashboard.go      dashboard_test.go
│           ├── limits.go         limits_test.go
│           └── templates/        # *.gohtml, embedded via go:embed
│               ├── layout.gohtml
│               ├── login.gohtml
│               ├── dashboard.gohtml
│               ├── device.gohtml
│               └── limits.gohtml
├── android/                      # Gradle project (Kotlin DSL)
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── core/                     # PURE KOTLIN JVM module — no Android deps
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/kotlin/com/pcontrol/core/
│   │       │   ├── Policy.kt         # Policy, Limit, Exclusion data classes
│   │       │   ├── PolicyEngine.kt   # evaluateApp / evaluateWeb / verdicts
│   │       │   ├── UsageDay.kt       # per-day counters, day-key helpers
│   │       │   └── DomainParser.kt   # URL → registrable domain
│   │       └── test/kotlin/com/pcontrol/core/
│   │           ├── PolicyEngineTest.kt
│   │           ├── UsageDayTest.kt
│   │           └── DomainParserTest.kt
│   └── app/                      # Android app module
│       ├── build.gradle.kts
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── kotlin/com/pcontrol/app/
│           │   │   ├── MainActivity.kt          # setup/permissions UI
│           │   │   ├── BlockedActivity.kt       # full-screen "time's up"
│           │   │   ├── TrackerService.kt        # foreground service, tick loop
│           │   │   ├── AppUsagePoller.kt        # UsageStatsManager wrapper
│           │   │   ├── BrowserAccessibilityService.kt
│           │   │   ├── BrowserRegistry.kt       # browser pkg → url-bar view id
│           │   │   ├── Enforcer.kt              # verdict → notification/block
│           │   │   ├── SyncClient.kt            # HTTP client for /api/v1/sync
│           │   │   ├── BootReceiver.kt          # restart service on boot
│           │   │   └── db/                      # Room: entities + DAO
│           │   │       ├── AppDatabase.kt
│           │   │       ├── UsageCounter.kt      # (day, kind, subject, seconds, syncedSeconds)
│           │   │       └── CachedPolicy.kt      # JSON blob + version
│           │   └── res/xml/accessibility_service_config.xml
│           └── test/kotlin/com/pcontrol/app/    # Robolectric/JVM tests
│               ├── SyncClientTest.kt
│               ├── EnforcerTest.kt
│               └── BrowserRegistryTest.kt
└── deploy/
    ├── pcontrold.service         # systemd unit
    └── Caddyfile                 # reverse proxy + automatic TLS
```

---

## 5. Data model (server, SQLite) — normative schema

`server/internal/store/migrations.sql` (applied on startup; use a
`schema_migrations(version)` table and run statements idempotently):

```sql
CREATE TABLE IF NOT EXISTS devices (
    id            INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    token_hash    TEXT NOT NULL UNIQUE,   -- hex(sha256(raw token))
    created_at    TEXT NOT NULL,          -- RFC3339 UTC
    last_seen_at  TEXT                    -- RFC3339 UTC, NULL until first sync
);

CREATE TABLE IF NOT EXISTS usage_events (
    id               INTEGER PRIMARY KEY,
    event_id         TEXT NOT NULL UNIQUE,  -- client-generated UUID (dedupe)
    device_id        INTEGER NOT NULL REFERENCES devices(id),
    kind             TEXT NOT NULL CHECK (kind IN ('app','web')),
    subject          TEXT NOT NULL,         -- package name or registrable domain
    label            TEXT NOT NULL DEFAULT '',  -- human name, e.g. "YouTube"
    day              TEXT NOT NULL,         -- 'YYYY-MM-DD', DEVICE-LOCAL date
    started_at       TEXT NOT NULL,         -- RFC3339 with device offset
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds >= 0)
);
CREATE INDEX IF NOT EXISTS idx_usage_device_day
    ON usage_events(device_id, day);

CREATE TABLE IF NOT EXISTS limits (
    id                  INTEGER PRIMARY KEY,
    device_id           INTEGER NOT NULL REFERENCES devices(id),
    kind                TEXT NOT NULL CHECK (kind IN ('app','web')),
    subject             TEXT NOT NULL,
    daily_limit_minutes INTEGER NOT NULL CHECK (daily_limit_minutes > 0),
    UNIQUE (device_id, kind, subject)
);

CREATE TABLE IF NOT EXISTS exclusions (      -- exempt from the TOTAL limit
    id        INTEGER PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id),
    kind      TEXT NOT NULL CHECK (kind IN ('app','web')),
    subject   TEXT NOT NULL,
    UNIQUE (device_id, kind, subject)
);

CREATE TABLE IF NOT EXISTS device_settings (
    device_id                 INTEGER PRIMARY KEY REFERENCES devices(id),
    total_daily_limit_minutes INTEGER,              -- NULL = no total limit
    warn_threshold_percent    INTEGER NOT NULL DEFAULT 90,
    policy_version            INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS sessions (        -- parent web-UI sessions
    token      TEXT PRIMARY KEY,             -- 32 random bytes, hex
    expires_at TEXT NOT NULL                 -- RFC3339 UTC
);
```

Rules:

- **`policy_version`** is bumped (+1) inside the same transaction as ANY change
  to `limits`, `exclusions`, or `device_settings` for that device. The client
  uses it to know its cache is stale.
- The **admin password** is NOT stored in the DB. `pcontrold` takes
  `--admin-password-hash <bcrypt>` (or env `PCONTROL_ADMIN_HASH`). Provide a
  `pcontrold hash-password` subcommand that reads a password from stdin and
  prints the bcrypt hash.
- Device tokens: generated server-side at registration as 32 random bytes,
  base64url-encoded; only the sha256 is stored; the raw token is shown ONCE in
  the web UI to be typed/pasted into the phone.

---

## 6. Policy semantics (normative — implement exactly)

Definitions:

- A **day** is the device-local calendar date (`YYYY-MM-DD`). All counters
  reset at local midnight. The client computes the day key; the server trusts
  the `day` field on events.
- `appSeconds(pkg)` = today's foreground seconds for package `pkg`.
- `webSeconds(domain)` = today's seconds a browser was foreground **with that
  registrable domain in its URL bar**. Web time is a *subset* of the browser's
  app time — never add app and web seconds together.
- **Domain matching**: a limit/exclusion on `youtube.com` matches
  `youtube.com`, `m.youtube.com`, `music.youtube.com` (suffix match on dot
  boundaries). `DomainParser` reduces a URL to its registrable domain
  (`https://m.youtube.com/watch?v=x` → `youtube.com`) using a small embedded
  public-suffix list of common suffixes (`com`, `org`, `net`, `co.uk`, `io`,
  `edu`, `gov` — a full PSL is overkill for v1).

Counted total (for the total daily limit):

```
countedTotal = Σ appSeconds(p)   for every package p NOT in app-exclusions
             − Σ webSeconds(d)   for every domain d in web-exclusions
clamped to ≥ 0
```

(Subtracting excluded domains lets e.g. `khanacademy.org` in Chrome not eat
the daily budget even though Chrome itself counts.)

Verdict rules, evaluated in this order (first BLOCK wins; else first WARN;
else ALLOW). `warn%` is `warn_threshold_percent` (default 90):

1. **Never-block list** — always ALLOW, regardless of anything:
   the default launcher/home app, `com.android.systemui`, the default dialer,
   `com.android.settings`, and the pcontrol app itself.
2. **Per-app limit**: if `pkg` has limit `L` minutes:
   `appSeconds(pkg) ≥ L*60` → BLOCK; `≥ L*60*warn%/100` → WARN.
   This applies **even if the app is in exclusions** (exclusions only affect
   the total limit).
3. **Per-site limit**: if the current browser domain matches limit `L`:
   `webSeconds(domain) ≥ L*60` → BLOCK (web); `≥ warn%` → WARN.
4. **Total limit**: if `total_daily_limit_minutes` is set and
   `countedTotal ≥ total*60`:
   - every non-browser app not in app-exclusions and not on the never-block
     list → BLOCK_APP;
   - **registered** browsers (present in `BrowserRegistry`) enter
     **restricted browsing mode** (below) instead of a plain block;
   - **unregistered** browsers → BLOCK_APP (their URL bar cannot be read, so
     per-domain gating is impossible — fail closed).
   `countedTotal ≥ total*60*warn%/100` → WARN.

### Restricted browsing mode (total limit hit)

Once the total limit is reached, a registered browser stays usable **only
for excluded (whitelisted) websites**. Evaluated every tick for the
foreground browser:

- Current session domain matches a web-exclusion (suffix rule) → ALLOW.
  Time accounting stays consistent automatically: excluded-domain seconds
  are subtracted from `countedTotal`, so whitelisted browsing never digs the
  deficit deeper.
- Domain readable but NOT excluded → BLOCK_WEB (BACK action, 2-strikes
  fallback to `BlockedActivity` — same mechanism as per-site blocks).
- Domain unreadable this tick (new-tab page, mid-typing, URL bar hidden by
  fullscreen video): use the last domain successfully read during the
  **current foreground session** of that browser — fullscreen hides the URL
  bar without navigating, so the last-read domain is still correct. If no
  domain has been read yet this session, allow a grace of 3 consecutive
  ticks (30s) so a URL can be typed, then BLOCK_WEB. Unknown ⇒ block:
  degradation is always fail-closed.
- "Foreground session" = the span since the browser last came to the
  foreground; the per-browser last-read-domain cache is cleared when the
  browser leaves the foreground.
- Rules 2–3 take precedence over restricted mode: a browser whose own
  per-app limit is exhausted is fully blocked (BLOCK_APP, no restricted
  mode), and an excluded domain whose own per-site limit is used up still
  gets BLOCK_WEB.

Enforcement actions:

- **WARN**: post one high-priority notification per (subject, day) —
  "YouTube: 27 of 30 minutes used." Never repeat for the same subject+day.
- **BLOCK (app)**: launch `BlockedActivity` (full-screen, shows which limit
  was hit and time remaining until midnight) every time the blocked app comes
  to the foreground.
- **BLOCK (web)**: the accessibility service performs
  `performGlobalAction(GLOBAL_ACTION_BACK)` and posts a "site blocked" toast;
  if the domain is still in the URL bar after 2 back-presses, fall back to
  launching `BlockedActivity`.

---

## 7. API contract (device ↔ server)

One endpoint. Auth: `Authorization: Bearer <device-token>`; the server hashes
the presented token and looks it up in `devices.token_hash`. Unknown token →
`401` with empty body.

### `POST /api/v1/sync`

Request:

```json
{
  "device_time": "2026-07-03T15:04:05+08:00",
  "policy_version": 12,
  "events": [
    {
      "event_id": "b1946ac9-...-uuid",
      "kind": "app",
      "subject": "com.google.android.youtube",
      "label": "YouTube",
      "day": "2026-07-03",
      "started_at": "2026-07-03T14:58:00+08:00",
      "duration_seconds": 120
    },
    {
      "event_id": "a7f3c2d1-...-uuid",
      "kind": "web",
      "subject": "youtube.com",
      "label": "youtube.com",
      "day": "2026-07-03",
      "started_at": "2026-07-03T14:58:00+08:00",
      "duration_seconds": 60
    }
  ]
}
```

Response `200`:

```json
{
  "accepted_event_ids": ["b1946ac9-...", "a7f3c2d1-..."],
  "policy": {
    "version": 13,
    "total_daily_limit_minutes": 120,
    "warn_threshold_percent": 90,
    "limits": [
      {"kind": "app", "subject": "com.google.android.youtube", "daily_limit_minutes": 30},
      {"kind": "web", "subject": "tiktok.com", "daily_limit_minutes": 15}
    ],
    "exclusions": [
      {"kind": "app", "subject": "com.duolingo"},
      {"kind": "web", "subject": "khanacademy.org"}
    ]
  }
}
```

Server behavior (single transaction):

1. Insert every event with `INSERT ... ON CONFLICT(event_id) DO NOTHING`;
   report ALL request event_ids as accepted (duplicates are already stored).
2. Update `devices.last_seen_at`.
3. If request `policy_version` equals the current one, return `"policy": null`
   (client keeps its cache); otherwise return the full policy.

Client behavior: on ANY sync failure (network, 5xx), keep events queued and
retry on the next 60s tick. Events are deltas of local counters, so nothing is
lost; `event_id` dedupe makes retries safe.

## 8. Web UI (parent-facing)

Server-rendered Go templates + HTMX (vendor `htmx.min.js` into the binary via
`go:embed`; no npm, no build step). Session cookie (`HttpOnly`, `Secure`,
`SameSite=Lax`, 30-day expiry). All non-`/login`, non-`/api` routes redirect
to `/login` without a valid session. All POSTs go through HTMX or plain forms.

| Route | Method | Purpose |
|---|---|---|
| `/login` | GET/POST | Password form → bcrypt check → session cookie |
| `/logout` | POST | Delete session |
| `/` | GET | Dashboard: per device, today's total vs limit, top apps and sites (label, minutes, limit, progress bar) |
| `/devices/new` | GET/POST | Register device; shows the raw token exactly once |
| `/devices/{id}` | GET | Usage detail; `?day=YYYY-MM-DD` picker; app table + site table |
| `/devices/{id}/limits` | GET | Manage limits, exclusions, total limit, warn % |
| `/devices/{id}/limits` | POST | Add limit `{kind, subject, minutes}` (HTMX row append) |
| `/devices/{id}/limits/{limitId}/delete` | POST | Remove limit |
| `/devices/{id}/exclusions` | POST | Add exclusion |
| `/devices/{id}/exclusions/{exclusionId}/delete` | POST | Remove exclusion |
| `/devices/{id}/settings` | POST | Set/clear total limit, warn % |

UI niceties (cheap, do them): subjects seen in usage data appear in a
`<datalist>` when adding a limit, so you pick `com.google.android.youtube`
from what was actually used instead of typing it.

## 9. Android client behavior (normative)

**Tick loop** (in `TrackerService`, a foreground service with a persistent
notification): every 10 seconds:

1. Ask `AppUsagePoller` for the current foreground package
   (`UsageStatsManager.queryEvents` over the last ~60s; the package of the
   latest `ACTIVITY_RESUMED`/`MOVE_TO_FOREGROUND` event wins). Screen off or
   no event → no attribution this tick.
2. If the package is a known browser (in `BrowserRegistry`), read the
   current domain from `BrowserAccessibilityService`'s **foreground-session
   cache**: the last successfully parsed URL-bar domain for that browser
   (updated on `TYPE_WINDOW_CONTENT_CHANGED`/`TYPE_WINDOW_STATE_CHANGED`),
   cleared when the browser leaves the foreground. An unreadable URL bar
   (fullscreen video, mid-typing) keeps the previous domain for the session.
3. Add 10s to the Room `UsageCounter` rows: `(today, 'app', pkg)` and, when in
   a browser with a readable URL, `(today, 'web', domain)`.
4. Run `PolicyEngine` on the fresh counters; hand the verdict to `Enforcer`.

**Sync loop**: every 60 seconds (and immediately on service start): for each
counter where `seconds > syncedSeconds`, build one event with
`duration_seconds = seconds - syncedSeconds` and a fresh UUID; POST the batch;
on success set `syncedSeconds = seconds` for the snapshot values sent and
persist any returned policy into `CachedPolicy`.

**BrowserRegistry** (data-driven map, unit-tested):

| Browser package | URL-bar `viewIdResourceName` |
|---|---|
| `com.android.chrome` | `com.android.chrome:id/url_bar` |
| `org.mozilla.firefox` | `org.mozilla.firefox:id/mozac_browser_toolbar_url_view` |
| `com.brave.browser` | `com.brave.browser:id/url_bar` |
| `com.microsoft.emmx` | `com.microsoft.emmx:id/url_bar` |

If a browser's URL can't be read, its time still counts as app time.

**Permissions/setup flow** (`MainActivity` is a checklist screen with a
button per step): usage access (`PACKAGE_USAGE_STATS` via Settings intent),
accessibility service enable, draw-over-apps (`SYSTEM_ALERT_WINDOW`),
notifications (`POST_NOTIFICATIONS`), ignore battery optimizations, then
"server URL + device token" entry (stored in `EncryptedSharedPreferences`).
`BootReceiver` restarts `TrackerService` after reboot.

**Time**: day keys come from `LocalDate.now()` (device zone). On day change
the engine simply reads rows for the new day key — no reset job needed.

---

## 10. flake.nix (starting point — commit as-is in Stage 1)

```nix
{
  description = "pcontrol - parental control server + Android client";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;               # Android SDK licenses
            android_sdk.accept_license = true;
          };
        };
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" ];
          buildToolsVersions = [ "34.0.0" ];
          includeEmulator = false;
          includeSystemImages = false;
        };
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            # server
            go_1_23
            gopls
            gotools
            sqlite
            # android
            jdk17
            gradle
            android-tools            # adb
            androidComposition.androidsdk
            kotlin-language-server
          ];
          ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
          shellHook = ''
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2"
            echo "pcontrol dev shell: go $(go version | cut -d' ' -f3), java $(java -version 2>&1 | head -1)"
          '';
        };
      });
}
```

Note for agents: `androidenv` can be flaky on `aarch64-darwin`. If
`nix develop` fails on the Android SDK derivation, remove
`androidComposition.androidsdk` from the shell, keep everything else, and
document in README.md that the Android SDK is installed via Android Studio
with `ANDROID_HOME` pointing at it. Do NOT block server stages on this.

---

## Stage 1: Repo scaffolding + dev environment

**Goal**: `nix develop` gives a working shell; empty-but-building Go module
and Android project; CI-style check scripts.
**Status**: Complete

Tasks (in order):

1. Write `flake.nix` exactly as in §10, plus `.gitignore` (Go, Gradle,
   Android Studio, `*.db`, `result`, `local.properties`).
2. `server/`: `go mod init pcontrol/server`; add `cmd/pcontrold/main.go` that
   starts an HTTP server with a `GET /healthz` → `200 ok` handler.
   TDD: `main` stays thin; put the handler/mux construction in
   `internal/web/router.go` with `router_test.go` using `httptest` asserting
   `/healthz` returns 200 and body `ok`.
3. `android/`: Gradle project with `:core` (pure Kotlin JVM, JUnit 5) and
   `:app` (Android application, Robolectric available in `testImplementation`).
   Add one trivial test in `:core` (e.g. `UsageDayTest` asserting a day-key
   helper formats `2026-07-03`) to prove the JVM test toolchain runs.
4. `README.md`: one-paragraph description, `nix develop`, how to run tests
   for each half.

**Success Criteria** (run from repo root):
- `nix develop -c go test ./...` (in `server/`) passes.
- `nix develop -c gradle :core:test` (in `android/`) passes.
- `go run ./cmd/pcontrold --help` prints usage; server serves `/healthz`.

## Stage 2: Server domain + storage (pure TDD core)

**Goal**: SQLite store with full schema; domain aggregation logic; everything
unit-tested. No HTTP yet beyond `/healthz`.
**Status**: Complete

Tasks:

1. `internal/domain/types.go`: `Kind` (`app`/`web`), `Event`, `Limit`,
   `Exclusion`, `Policy`, `UsageTotal{Kind, Subject, Label, Seconds}`.
2. `internal/domain/aggregate.go` + tests first:
   - `CountedTotalSeconds(appTotals, webTotals []UsageTotal, exclusions []Exclusion) int`
     implementing the §6 formula. Test cases: no exclusions; excluded app;
     excluded domain subtracts from total; result clamped at 0.
   - `MatchesDomain(subject, domain string) bool` — suffix-on-dot-boundary.
     Tests: exact, subdomain, non-match (`notyoutube.com` vs `youtube.com`).
3. `internal/store`: `Open(path string)` applies `migrations.sql` (§5).
   All store tests run against a temp-file DB (`t.TempDir()`).
   Write tests first for each method:
   - `CreateDevice(name) (Device, rawToken)`, `DeviceByToken(raw)` (hash
     lookup), `TouchLastSeen`.
   - `InsertEvents([]Event) error` — duplicate `event_id` silently ignored
     (test: insert same batch twice, count rows once).
   - `UsageTotals(deviceID, day) ([]UsageTotal appKind, []UsageTotal webKind)`
     — `SUM(duration_seconds) GROUP BY kind, subject`, labels via latest
     non-empty label.
   - Policy CRUD: `SetLimit`, `DeleteLimit`, `AddExclusion`,
     `DeleteExclusion`, `SetTotalLimit`, `SetWarnPercent`, `GetPolicy`.
     Test: **every mutation bumps `policy_version` by exactly 1** in the same
     transaction.

**Success Criteria**: `go test ./...` green; `go vet ./...` clean; a
`store_test.go` round-trip proves schema applies from empty file.

## Stage 3: Device API + web UI

**Goal**: complete server: `/api/v1/sync` per §7, all web routes per §8,
session auth. The server is feature-complete and deployable after this stage.
**Status**: Complete

Tasks:

1. `internal/api/auth.go` (test first): bearer-token middleware → 401 on
   missing/unknown token; attaches `deviceID` to context.
2. `internal/api/sync.go` (tests first, using `httptest` + a real temp-file
   store): happy path returns accepted ids + policy; same batch twice → no
   duplicate rows; matching `policy_version` → `"policy": null`; malformed
   JSON → 400; oversized body (>1 MiB) → 413.
3. `internal/web/auth.go`: login (bcrypt compare against configured hash),
   session create/check middleware, logout. Tests: wrong password → 401 +
   re-rendered form; right password → cookie set → `/` reachable.
4. Dashboard + device pages (tests assert key strings in rendered HTML:
   subject labels, minute counts, limit values).
5. Limits/exclusions/settings POST handlers (tests: add limit → row appears
   in `GetPolicy` and in the page; delete removes it; total limit set/clear).
6. `cmd/pcontrold`: flags `--db`, `--listen` (default `127.0.0.1:8080`),
   `--admin-password-hash` / env `PCONTROL_ADMIN_HASH`; subcommand
   `hash-password`. Embed templates + htmx via `go:embed`.
7. `deploy/`: `Caddyfile` (`pcontrol.example.com { reverse_proxy 127.0.0.1:8080 }`)
   and `pcontrold.service` (systemd, `DynamicUser=yes`,
   `StateDirectory=pcontrol`). README section: VPS install steps + note that
   the SQLite file is the only state to back up.

**Success Criteria**: `go test ./...` green. Manual smoke test: run locally,
register a device in the UI, copy token, `curl -X POST /api/v1/sync` with two
events, see them on the dashboard, add a limit, re-sync and receive it in the
policy JSON.

## Stage 4: Android client — tracking + sync (no enforcement yet)

**Goal**: the phone records app + web usage into Room and syncs it to the
server; dashboard shows real data. All logic in `:core` and all JVM-testable
`:app` classes are TDD'd; thin Android glue (service/manifest wiring) is
verified manually.
**Status**: Not Started

Tasks:

1. `:core` `DomainParser` (tests first): URL string → registrable domain per
   §6. Cases: plain domain, `m.` subdomain, `co.uk`, port, path/query, non-URL
   text (URL bar mid-typing) → null, IP address → the IP.
2. `:core` `UsageDay` (tests first): day-key from `LocalDateTime` + zone;
   counter merge helper.
3. `:app` Room setup: `UsageCounter(day, kind, subject, label, seconds,
   syncedSeconds)` with `@Entity(primaryKeys=[day,kind,subject])`,
   `CachedPolicy(id=1, version, json)`. DAO tested with Robolectric +
   in-memory Room: increment upsert, unsynced-delta query, mark-synced.
4. `SyncClient` (tests first, against `okhttp3.mockwebserver` or a plain
   `com.sun.net.httpserver` fixture): builds §7 request JSON from unsynced
   deltas, parses policy response, bearer header set, network failure →
   deltas remain unsynced. Use `kotlinx.serialization` for JSON.
5. `AppUsagePoller`: extract the "pick foreground pkg from a list of usage
   events" decision into a pure function; test it with fabricated event lists
   (resume/pause orderings, empty list).
6. `BrowserRegistry` (tests): package → url-bar id map; unknown package →
   null. `BrowserAccessibilityService`: on events from registered browsers,
   `findAccessibilityNodeInfosByViewId(urlBarId)` → text → `DomainParser` →
   update a per-browser **foreground-session cache**
   (`browserPkg → last successfully parsed domain`). Unparseable URL-bar
   text (mid-typing, new-tab page, hidden bar) does NOT overwrite the last
   good domain; the entry is cleared when that browser leaves the foreground
   (`TrackerService` calls `onForegroundChanged(pkg)` every tick). The cache
   is a plain class — unit-test update / keep-on-null / clear-on-leave.
7. `TrackerService`: 10s tick per §9 (attribution + counter writes),
   60s sync. Foreground notification "pcontrol active". `BootReceiver`.
8. `MainActivity` setup checklist per §9 (permission status + intent buttons,
   server URL + token entry).

Manual verification checklist (document results in the commit message):
install on the phone via `adb install`, grant all permissions, open YouTube
2 min and `youtube.com` in Chrome 1 min, confirm both rows on the web
dashboard with plausible durations; reboot phone, confirm service resumes.

**Success Criteria**: `gradle :core:test :app:testDebugUnitTest` green;
manual checklist passes end-to-end against the real server.

## Stage 5: Enforcement — warn, then block

**Goal**: limits actually bite: notification at 90%, overlay/back-navigation
at 100%, per §6 — with ONE temporary simplification: when the total limit is
hit, browsers are blocked like plain apps. Stage 6 replaces that with
restricted browsing mode.
**Status**: Not Started

Tasks:

1. `:core` `PolicyEngine` (this is the heart — exhaustive tests first, ~15
   cases): implement §6 verdict rules exactly. Required test cases:
   - per-app limit: under warn / at warn / at 100% / over.
   - per-app limit on an excluded app still blocks.
   - per-site: subdomain matches parent-domain limit; unrelated domain ALLOW.
   - total limit: countedTotal math with excluded app and excluded domain
     (subtraction + clamp); WARN at 90%; BLOCK applies to a random app but
     NOT to an excluded app, NOT to never-block packages. In THIS stage
     browsers block like plain apps when the total is hit (write that test
     now; Stage 6 will change it to restricted mode).
   - no policy / no limits → ALLOW everything.
   - warn threshold respected when set to a non-default (e.g. 80).
2. `Enforcer` (Robolectric tests): WARN posts exactly one notification per
   (subject, day) — second WARN same day is a no-op (persist warned-set in
   Room, keyed by day); BLOCK(app) fires `BlockedActivity` intent; BLOCK(web)
   requests BACK action, with the 2-strikes fallback to `BlockedActivity`.
3. `BlockedActivity`: full-screen, non-dismissable (finishes to home via
   `Intent.CATEGORY_HOME`), shows subject label, which limit was hit, and
   "resets at midnight".
4. Wire `PolicyEngine` + `Enforcer` into the `TrackerService` tick (verdict
   evaluated every tick for the current app/domain).
5. Dashboard polish: show BLOCKED badge on subjects past their limit
   (server-side re-uses `domain.CountedTotalSeconds` + limits — small tests).

Manual verification checklist: set a 1-minute limit on a test app → warn
notification appears ~54s, overlay at 60s, overlay re-appears when reopening
the app; 1-minute limit on `example.com` → Chrome navigates back at 60s;
1-minute total limit → everything except excluded apps blocks; excluded app
stays usable past the total; all clears after (simulated) midnight — set the
limit higher instead of waiting, or change device date on a test device only.

**Success Criteria**: all JVM/Robolectric tests green; full manual checklist
passes on the real phone.

## Stage 6: Restricted browsing mode — whitelisted sites survive the total limit

**Goal**: once the total daily limit is hit, registered browsers remain
usable for excluded (whitelisted) websites only; everything else in the
browser blocks, per §6 "Restricted browsing mode". This completes v1.
**Status**: Not Started

Tasks:

1. `:core` `PolicyEngine` extension (tests first). Give the engine a browser
   context so it stays a pure function (all state lives in the caller):

   ```kotlin
   data class BrowserContext(
       val isRegisteredBrowser: Boolean,
       val currentDomain: String?,   // foreground-session cache; null = never read
       val ticksWithoutDomain: Int   // consecutive ticks with a null domain
   )
   // browser == null for non-browser apps
   fun evaluate(pkg: String, browser: BrowserContext?, usage: UsageSnapshot,
                policy: Policy, now: LocalDateTime): Decision
   ```

   Verdicts distinguish `BLOCK_APP` (overlay) from `BLOCK_WEB` (BACK with
   overlay fallback). Required test cases:
   - total hit + registered browser + excluded domain → ALLOW.
   - total hit + registered browser + subdomain of an excluded domain
     (`www.khanacademy.org` vs exclusion `khanacademy.org`) → ALLOW.
   - total hit + registered browser + non-excluded domain → BLOCK_WEB.
   - total hit + registered browser + null domain, `ticksWithoutDomain ≤ 3`
     → ALLOW (grace); `> 3` → BLOCK_WEB.
   - total hit + unregistered browser → BLOCK_APP.
   - total hit + browser whose own per-app limit is also exhausted →
     BLOCK_APP even when showing an excluded domain (rule 2 precedes).
   - excluded domain whose own per-site limit is used up → BLOCK_WEB even in
     restricted mode.
   - total NOT hit → browser verdicts identical to Stage 5 (regression
     cases: per-site limit warn/block still work).
2. `TrackerService`: maintain `ticksWithoutDomain` for the current foreground
   browser session (reset to 0 on foreground change and on every successful
   domain read); build `BrowserContext` each tick and pass it to the engine.
   Extract the counter into a small pure class and unit-test it.
3. `Enforcer`: `BLOCK_WEB` in restricted mode reuses the BACK + 2-strikes
   overlay fallback. `BlockedActivity` gets a variant message for restricted
   mode: "Daily screen time is used up. Allowed sites:" followed by the
   web-exclusion list (read from `CachedPolicy`), so the kid knows what still
   works.
4. Web UI: on `/devices/{id}/limits`, label web exclusions
   "always allowed — usable even after the daily limit" so the intent is
   visible where they are managed.

Manual verification checklist: set a 1-minute total limit and exclude
`khanacademy.org`; burn the minute; then verify: a non-excluded app →
overlay; `khanacademy.org` in Chrome loads and stays usable, including
fullscreen video (URL bar hidden); navigating to `youtube.com` in the same
Chrome session → bounced back within ~10s; a fresh new-tab page allows ~30s
of typing before blocking; an unregistered browser (if installed) is fully
blocked; the excluded app still works; warn notification appeared at ~54s.

**Success Criteria**: all JVM/Robolectric tests green, including unchanged
Stage 5 regressions; manual checklist passes on the real phone;
`IMPLEMENTATION_PLAN.md` deleted in the final commit per repo convention.

---

## Risks & known limitations (accepted for v1)

- **URL-bar reading is browser-version fragile.** If Chrome renames its view
  id, web tracking silently degrades to app-level tracking of Chrome. The
  `BrowserRegistry` map is the single place to patch.
- **Child can revoke permissions or uninstall.** V1 has no tamper-proofing
  (see Non-goals). The dashboard's `last_seen_at` going stale is the tell.
- **Incognito/other browsers** not in the registry are only tracked as app
  time, and are fully blocked in restricted mode (no per-domain gating
  possible). Practical mitigation: set a per-app limit on unregistered
  browsers. Restricted mode always fails closed: an unreadable URL means
  "block", never "allow".
- **Usage attribution granularity is 10s ticks** — durations are ±10s, which
  is fine for hour-scale limits.
- **Device clock changes** can shift the day key. Acceptable for v1.
