# AGENTS.md — guide for coding agents

pcontrol is a self-hosted parental-control system: a Go server (JSON API +
web dashboard) on a VPS, and a Kotlin Android client that tracks app/web
usage, syncs it, and enforces daily time limits locally.

## Repository layout

```
server/           Go module `pcontrol/server` (Go 1.26)
  cmd/pcontrold/  Main binary (also `hash-password` subcommand)
  internal/
    domain/       Plain structs + pure aggregation helpers
    store/        SQLite (modernc.org/sqlite, pure Go) + migrations.sql
    api/          Device-facing JSON API (POST /api/v1/sync, bearer auth)
    web/          Admin dashboard (html/template + HTMX, session auth)
android/          Gradle project (Kotlin, JDK 17, Gradle 9.4.1)
  core/           Pure Kotlin JVM module — PolicyEngine, domain logic, no Android deps
  app/            Android app — TrackerService, enforcement, Room DB, sync
deploy/           systemd unit + Caddyfile + Unraid Docker template (deploy/unraid/)
docs/plans/       Numbered plan files (01_… is the normative original spec)
```

## Dev environment & commands

Use the Nix dev shell (`nix develop`) — it provides Go, JDK 17, Gradle, the
Android SDK, and sqlite. There is **no committed Gradle wrapper**; use the
dev shell's `gradle` locally (CI generates a wrapper on the fly with
`gradle wrapper --gradle-version 9.4.1`).

```sh
# Server: tests + vet (run both before any commit touching server/)
cd server && go test ./... && go vet ./...

# Android: all JVM unit tests (what CI runs)
cd android && gradle test

# Narrower: core module only / app unit tests only
cd android && gradle :core:test
cd android && gradle :app:testDebugUnitTest

# Run the server locally
cd server && go run ./cmd/pcontrold \
    --listen 127.0.0.1:8080 \
    --admin-password-hash "$(go run ./cmd/pcontrold hash-password <<< 'my-password')"

# Build a signed release APK locally (needs android/key.properties + android/release.jks,
# both gitignored — see "Local release signing" gotcha below)
cd android && gradle :app:assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

CI (`.github/workflows/`): `server-tests.yml` runs `go test -count=1 ./...`,
`android-tests.yml` runs `gradle test`, `server-image.yml` builds and
publishes a multi-arch Docker image to GHCR, and `android-build.yml` builds
a release APK when a tag matching `android-*` is pushed. Pushes trigger CI on
`main` only; PRs get their own runs.

## Architecture invariants

- **Sync protocol** (`POST /api/v1/sync`): the client uploads usage-event
  deltas (idempotent via client-generated UUID `event_id`; the server
  dedupes with `ON CONFLICT DO NOTHING`) and downloads the policy only when
  its cached `policy_version` differs. Request/response DTOs live in
  `server/internal/api/sync.go` (Go) and
  `android/app/.../SyncClient.kt` (Kotlin) — **keep them in lock-step**.
- **Backward compatibility is a hard requirement**: the APK and the server
  binary deploy independently. Every new JSON field must be optional
  (pointer types in Go, nullable-with-default in Kotlin). Never remove or
  rename an existing field. The Kotlin decoder uses
  `ignoreUnknownKeys = true`; Go ignores unknown keys by default.
- **JSON naming**: snake_case on the wire. Kotlin DTOs use
  `@SerialName("snake_case")`; Go structs use json tags.
- **Enforcement is local**: the phone blocks apps/sites from its cached
  policy; the server is the source of truth for policy and the sink for
  usage. The dashboard is pull-only — no push channel exists.
- **`:core` stays pure JVM**: no Android imports. Anything needing Android
  APIs gets a thin adapter in `:app` (see `UsageStatsAdapter.kt`) so logic
  remains unit-testable without Robolectric.
- The SQLite file is the only server state; the schema lives in
  `server/internal/store/migrations.sql`.

## Gotchas (learned the hard way — do not rediscover)

- **`migrations.sql` runs in full on every startup** (`store.Open()`).
  Every statement in it must be idempotent (`CREATE TABLE IF NOT EXISTS`,
  `INSERT OR IGNORE`). A bare `ALTER TABLE ADD COLUMN` there will crash the
  server on its second boot — schema changes need a guarded, versioned
  migration step in Go (check `pragma_table_info`, then record the version
  in `schema_migrations`).
- **Foreign keys are not enforced**: the schema declares `REFERENCES` but
  `PRAGMA foreign_keys` is never enabled and there is no
  `ON DELETE CASCADE`. Deleting a device must delete child rows
  (`usage_events`, `limits`, `exclusions`, `device_settings`) explicitly in
  a transaction.
- **`TrackerService.onSync()` early-returns when there are no unsynced
  events.** Anything that must reach the server even while the phone is
  idle (heartbeats, status fields) has to account for this.
- Timestamps are stored as RFC 3339 UTC strings; day keys are
  `"YYYY-MM-DD"` in **device-local** time — the server trusts the `day`
  field on events and never recomputes it.
- Device bearer tokens are stored only as `hex(sha256(token))`; the raw
  token is shown once at registration and cannot be recovered.
- Web templates are embedded via `go:embed` in `web/render.go`; adding a
  template means adding a `.gohtml` file under `web/templates/` (the glob
  picks it up) and a view-model struct in `web/templatesData.go`.
- **Dashboard status needs visible text.** A timestamp in a badge `title`
  attribute is hover-only and does not satisfy a requirement to show it;
  render report timestamps as card text and cover both populated and `never`
  states in dashboard tests.
- **Session cookie `Secure` must follow the transport, not be hard-coded.**
  A hard-coded `Secure: true` makes the browser discard the session cookie
  over plain HTTP, so login silently fails on a LAN-only Unraid deploy
  opened as `http://unraid-ip:7285/` — the password is accepted, the
  session is created, but the next request looks unauthenticated and
  bounces back to `/login`. Set `Secure: r.TLS != nil` (login + logout).
  Caveat: behind a TLS-terminating reverse proxy `r.TLS` is nil inside the
  container, so the cookie won't be `Secure` there (forwarded-header
  trust is intentionally not implemented yet).
- **`go:embed static/*` stores files under the `static/` prefix.** Serving
  them at `/static/*` requires `fs.Sub(staticFS, "static")` before
  `http.FileServer` — a bare `http.FileServer(http.FS(staticFS))` behind
  `http.StripPrefix("/static/", ...)` returns 404 because the stripped
  path (`htmx.min.js`) doesn't exist at the embed root (`static/htmx.min.js`).
- **Local release signing is opt-in via a gitignored `key.properties`.**
  `android/app/build.gradle.kts` reads `android/key.properties` (pointing
  at a local `android/release.jks`) **only when that file exists**; when
  absent (as in CI), the `release` build type produces an unsigned APK and
  the `android-*` tag workflow signs it separately with `apksigner`.
  The local dev key is **not** the canonical release key — an APK signed
  locally cannot share install lineage with a CI-signed `android-*`
  release (different signing certs = different signers in Android's eyes).
  See `docs/plans/06_local_signed_apk.md` for the full setup.
- **Signature continuity (auto-update, critical).** Android refuses to
  update an app whose new APK is signed with a different certificate than the
  installed one. The CI keystore and the local dev `release.jks` are
  different keys (see plan 06). Auto-update therefore only works between two
  *CI-signed* releases. A locally-built install trying to auto-update to a
  CI-signed APK will hit the signature mismatch — `SignatureVerifier` in
  Stage 5 pre-verifies and behaves gracefully (returns `SIGNATURE_MISMATCH`)
  instead of presenting a doomed install dialog.
- **Auto-update is self-update driven.** The update mechanism ships inside a
  release; users on `1.0.0` (before the feature existed) must install the
  first auto-update-enabled release manually. Subsequent releases
  self-update.
- **Update check gate is independent of sync.** The update-check runs from
  the `TrackerService` tick loop, not from `onSync()`, so an idle phone with
  no unsynced events still checks for updates. See gotcha above about
  `onSync()` early-return.
- **`targetSdk 37` ⇒ no `file://` URIs.** Auto-update install must use
  `FileProvider` `content://` URIs with `FLAG_GRANT_READ_URI_PERMISSION`.
  The deprecated `ACTION_INSTALL_PACKAGE` is never used.
- **`REQUEST_INSTALL_PACKAGES` is a per-source appop, not a manifest-only
  grant.** The parent must, one time, open
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` and allow our app. This is
  surfaced in the `MainActivity` permission checklist.
- **No silent install for a normal app.** The system install confirmation
  dialog is unavoidable unless the app is device/profile owner. Auto-update
  means *auto-check + auto-download + auto-prompt*, not auto-install.
- **Tag normalization is required.** Release `tag_name` is `android-v1.2.3`,
  release `name` is `v1.2.3`, installed `versionName` is `1.2.3`.
  `Version.parse` strips `android-` then `v` before semver comparison. Never
  compare raw strings.
- **`getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES` across API levels.**
  Tested on minSdk 26. If a device returns `null` signingInfo for an archive
  path, `SignatureVerifier` degrades to "trust the installer" (returns `true`)
  rather than blocking a valid update.
- **Download to app-private cache, never shared storage.**
  `context.cacheDir/updates/` avoids runtime storage permissions and is
  auto-purged by the OS. Always delete on failure and superseded siblings to
  avoid a half-downloaded APK causing a confusing install-fail.
- **GitHub unauthenticated rate limit is 60/hr.** The 24h cadence is far
  under that. The "Check for updates" button in MainActivity calls
  `runOnce(force = true)`, which intentionally bypasses the
  `lastUpdateCheckMs` gate so the user can always check immediately. The
  practical rate-limit protection against rapid manual taps is the HTTP
  403 from GitHub itself (60/hr).

- **`versionName`/`versionCode` must match the release tag — CI injects them.**
  `build.gradle.kts` hardcodes defaults (`versionName`, `versionCode`) for
  local dev builds, but `android-build.yml` extracts the numeric version from
  the `android-vX.Y.Z` tag and passes it via `-PappVersionName` /
  `-PappVersionCode` gradle properties, overriding the defaults. **Every
  release must bump the tag version** — the APK's `versionName` comes from
  the tag, not from `build.gradle.kts`. A mismatch (e.g. hardcoded `1.0.0`
  while tags say `0.0.x`) silently breaks any version-comparison feature
  (including auto-update: `Version.needsUpdate("1.0.0", "0.0.4")` →
  `UP_TO_DATE` because 1.0.0 > 0.0.4). When adding features that read
  `BuildConfig.VERSION_NAME`, verify what the released APK actually contains.
- **The `TrackerService` tick loop is timing-sensitive — never await
  blocking I/O inline.** The 10-second tick (`onTick()`) is the heartbeat of
  usage tracking. `onSync()` runs every 60s and the update check every 24h,
  both inside the same `while (true) { ... delay(10s) }` loop. A blocking
  network call awaited inline (e.g. an APK download) stalls the entire loop,
  silently pausing usage tracking for the duration. Launch long-running or
  blocking side-work on a **separate coroutine** (`scope.launch { ... }`)
  so the tick loop continues independently. See the existing gotcha about
  `onSync()` early-return for related context.
- **HyperOS blocks background activity starts even with draw-over-other-apps.**
  Never use `startActivity` as an automatic enforcement surface: Xiaomi can
  reject it with `Abort background activity starts`/`MIUIOP(10021)`. The bound
  accessibility service owns a `TYPE_ACCESSIBILITY_OVERLAY`; presentation
  failures must attempt `GLOBAL_ACTION_HOME` and notify, not fall back to an
  activity. On HyperOS 3/API 36 an `AccessibilityService` is not a visual
  context: create the overlay context from `createDisplayContext(defaultDisplay)`
  before `createWindowContext`, or it throws before `addView`. Keep foreground
  generations and overlay mutations serialized there.

## Conventions

- Follow the numbered plan files in `docs/plans/`. Larger work gets a new
  `NN_name.md` plan there first; update each stage's `**Status**` line as
  you go.
- TDD: write the failing test first. Mirror existing test files —
  `api/sync_test.go` and `web/dashboard_test.go` (key-string assertions
  against rendered HTML) on the server; `SyncClientTest.kt` /
  `PolicyEngineTest.kt` on the Kotlin side. Tests insert fixture rows
  directly via `store.DB.Exec` where no store method exists.
- Server handlers validate strictly and fail fast with plain
  `http.Error(...)` — match the existing 400/401/500 patterns in
  `api/sync.go`. Best-effort side writes (e.g. `TouchLastSeen`) log and
  continue rather than failing the request.
- Commit messages: `area: imperative summary`, e.g.
  `server/store: persist device battery status (Stage 1.2)`.
- No trailing whitespace, including on blank lines.
- Don't introduce new dependencies or tools without strong justification —
  the stack is deliberately stdlib `net/http`, html/template + HTMX, and
  pure-Go SQLite.
