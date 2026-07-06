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
deploy/           systemd unit + Caddyfile
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
```

CI (`.github/workflows/`): `server-tests.yml` runs `go test -count=1 ./...`,
`android-tests.yml` runs `gradle test`, and `android-build.yml` builds a
release APK when a tag matching `android-*` is pushed. Pushes trigger CI on
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
