# 12 — Daily usage reports by email (Issue #59)

**Status: Complete — implementation and review done, all tests green**

> Note: reports are sent a configurable delay after local midnight (default
> 3h, `--report-send-after` / `PCONTROL_REPORT_SEND_AFTER`) so late client-side
> usage ingestion is captured. See the "Send-after delay" design note below.

## Goal

Per GitHub issue #59, server/parent-side feature:

- Each device has a **configurable list of email addresses** that receive a daily
  usage report.
- If at least one recipient is configured, a **daily usage report is sent after
  midnight** in the device's timezone.
- The **device's timezone is configurable** (server-side), so "after midnight"
  and "yesterday" are computed per device.

## Design decisions

1. **Email transport**: stdlib `net/smtp` (`smtp.SendMail`). No new dependency
   (AGENTS.md: no new deps without strong justification). SMTP credentials come
   from new flags / `PCONTROL_SMTP_*` env. `smtp.SendMail` opportunistically
   upgrades to STARTTLS when the server advertises it (standard on port 587).
   Implicit-TLS (465) is not supported — documented.
2. **Timezone is server-side only** for this issue. It drives the report
   scheduler's "after midnight" and the "yesterday" day key per device. We do
   **not** push it to the Android client over the sync protocol in this issue —
   the Android `day` keys are already device-local, so no wire change is
   required and none is made. (Future enhancement: ship it on `policyJSON` as an
   optional field.)
3. **Report content**: plain-text email. Headers `From/To/Subject/Date/
   MIME-Version/Content-Type: text/plain; charset=utf-8`. Body: device name,
   report day, total counted minutes (respecting exclusions), top apps, top
   websites. Built from existing aggregation (`UsageTotals`,
   `CountedTotalSeconds`, exclusions from `GetPolicy`).
4. **Scheduling/idempotency**: a background goroutine with a 1-minute ticker in
   `main()`. For each device with ≥1 recipient, compute local "now" in the
   device's timezone (default UTC when unset); the report day = the local date
   minus one day. If `daily_report_log` has no row for (device, day), build and
   send; on success insert the log row (`INSERT OR IGNORE`). A failed send is
   retried on the next tick. Restarts can't double-send because the log row is
   durable before any later send.
4a. **Send-after delay** (`--report-send-after`, default `3h`): a report is only
   due once `SendAfter` has elapsed past local midnight in the device's
   timezone. This is a gate, not a reschedule — the report still covers the
   previous day, but waits so client-side usage events that sync late (device
   offline, retried syncs) land before the report is compiled. The boundary is
   `(local midnight of today) + SendAfter` in absolute time (`midnight.Add`),
   so a DST transition cannot shift it. `NewSender` applies the 3h default when
   `Config.SendAfter` is 0; setting `Sender.SendAfter = 0` after construction
   bypasses the default and sends as soon as the day rolls over.
5. **Config behavior**: if SMTP is not configured (`--smtp-host` empty) the job
   is not started (single log line). A device with no recipients is skipped.

## Schema changes (versioned `migrateV3`, NOT migrations.sql)

New `migrateV3(db)` in `store.go`, called from `Open()` after `migrateV2`,
guarded + idempotent, then `INSERT OR IGNORE INTO schema_migrations
(version) VALUES (3)`:

- `ALTER TABLE device_settings ADD COLUMN timezone TEXT` — guarded by
  `pragma_table_info('device_settings')` (mirror `migrateV2`'s `columnExists`).
- `CREATE TABLE IF NOT EXISTS device_email_recipients (
    device_id INTEGER NOT NULL,
    email     TEXT NOT NULL,
    position  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, email))` — `PRIMARY KEY` dedupes.
- `CREATE TABLE IF NOT EXISTS daily_report_log (
    device_id INTEGER NOT NULL,
    day       TEXT NOT NULL,
    sent_at   TEXT NOT NULL,
    PRIMARY KEY (device_id, day))`.

`DeleteDevice` cleanup list (devices.go) gains `device_email_recipients` and
`daily_report_log` (foreign keys are not enforced).

## Store methods — new file `server/internal/store/reporting.go`

- `SetEmailRecipients(deviceID int64, emails []string) error` — tx: delete all
  for device, then insert deduped emails in order with `position`.
- `EmailRecipients(deviceID int64) ([]string, error)` — `ORDER BY position, rowid`.
- `SetTimeZone(deviceID int64, tz string) error` — ensure `device_settings`
  row exists, `UPDATE ... SET timezone = ?` (empty string clears it).
- `TimeZone(deviceID int64) (string, error)` — read; may be `""`.
- `ReportTargets() ([]domain.ReportTarget, error)` — devices that have ≥1
  recipient, with name + timezone (JOIN `device_email_recipients`,
  `device_settings`, `devices`).
- `ReportSent(deviceID int64, day string) (bool, error)`.
- `MarkReportSent(deviceID int64, day string, sentAt time.Time) error`.

## Domain

Add to `domain/types.go`:

```go
// ReportTarget is a device that has at least one email recipient configured.
type ReportTarget struct {
    DeviceID int64
    Name     string
    TimeZone string // IANA name; may be "" (means UTC)
}
```

## Report package — new `server/internal/report/`

- `report.go`: `Sender` with store, SMTP config, `now func() time.Time` (fake
  for tests), logger. `Run(ctx)` starts a 1-minute ticker goroutine.
- `reportDay(now, loc)` helper → yesterday `"2006-01-02"` in `loc`.
- `buildReportBody(target, day, totals…)` → plain text; pure function, unit
  tested.
- `sendEmail(cfg, from, to, subject, body)` → `smtp.SendMail`.
- A send for a device with an invalid/unknown timezone falls back to UTC and
  logs once (never panics).

## main.go + config

New flags in `cmd/pcontrold/main.go` (each with `PCONTROL_SMTP_*` env fallback
mirroring `PCONTROL_ADMIN_HASH`):
`--smtp-host`, `--smtp-port` (default 587), `--smtp-username`,
`--smtp-password`, `--smtp-from`, `--report-send-after` (default 3h). If
`--smtp-host` is empty → log "daily email
reports disabled (no SMTP host)" and skip. Else start `report.Run` in a
goroutine before `http.ListenAndServe`.

## Web UI

- Routes (`router.go`): `POST /devices/{id}/recipients` → `deviceRecipients()`,
  `POST /devices/{id}/timezone` → `deviceTimeZone()`.
- Handlers in `dashboard.go`: validate emails (non-empty, contains `@` and
  `.`), validate timezone via `time.LoadLocation`, redirect back to device page.
- `deviceDetailData` gains `TimeZone string` and `Emails []string`.
- `device.gohtml` ⚙️ Device Settings block: timezone text input (placeholder
  `e.g. America/New_York`, current value shown, hint "used for daily report
  timing") and recipients textarea (one email per line, current recipients
  prefilled).

## Deploy + docs

- `deploy/pcontrold.service`: add commented `Environment=PCONTROL_SMTP_*`
  and `PCONTROL_REPORT_SEND_AFTER` lines.
- `deploy/Dockerfile` (and `deploy/unraid/` template if it carries env):
  document SMTP env.
- `README.md`: document SMTP flags/env + feature.
- `docs/ARCHITECTURE.md` + `docs/REQUIREMENTS.md`: add the daily-report
  requirement and background-job architecture note.
- This plan file's `**Status**` line updated as stages complete.
- AGENTS.md: add a gotcha if a real one is learned (e.g. smtp.SendMail/STARTTLS
  behavior).

## Tests (TDD, mirror existing files)

- `server/internal/store/store_test.go` — migration V3 recorded; new tables
  exist; `timezone` column exists on `device_settings`.
- `server/internal/store/reporting_test.go` — recipients set/get/replace/
  dedupe/order; timezone set/get/clear; `ReportTargets` returns only devices
  with recipients; `ReportSent`/`MarkReportSent` idempotency.
- `server/internal/report/report_test.go` — `reportDay` (fixed fake `now`,
  DST boundary zone), `buildReportBody` content, scheduler sends once per
  device/day with fake clock and no double-send on restart, skip-when-no-
  recipients, SMTP-not-configured skip, the send-after delay (default 3h;
  before/at the boundary), `SendAfter=0` sends at midnight, and the delay
  driven by the device timezone.
- `server/internal/web/dashboard_test.go` — device detail page renders the
  timezone input + recipients textarea with current values (key-string
  assertions); `POST /devices/{id}/timezone` + `/recipients` persist and
  redirect.
- `server/internal/api/sync_test.go` — unchanged; confirms backward compat.

## Ordered implementation steps

1. ✅ `migrateV3` + `DeleteDevice` cleanup + store migration test.
2. ✅ `domain.ReportTarget` + `reporting.go` store methods + tests.
3. ✅ `report` package (message builder + scheduler + sender) + tests (fake clock).
4. ✅ `main.go` SMTP flags/env + start job; deploy + README + docs.
5. ✅ Web UI routes/handlers/template + tests.
6. ✅ `cd server && go test ./... && go vet ./...` — all green.
7. ✅ Reviewer pass; address findings (CRLF header-injection hardening, validEmail/rename guards, device-existence 404s, DST fall-back test); re-run tests.
