# 15 — Fix multi-day tracker outages: dataSync FGS timeout + usage backfill

## Diagnosis (from the affected device, Xiaomi 25097RP43C / Android 16 / HyperOS 3.0 V816)

Device evidence collected over adb (logcat spans 08-21 → 09-09):

```
08-21 10:25:10  ForegroundServiceDidNotStopInTimeException:
               A foreground service of type dataSync did not stop within its
               timeout: com.pcontrol.app/.TrackerService        ← process killed
08-21 10:25:22  ForegroundServiceStartNotAllowedException:
               Time limit already exhausted for FGS type dataSync ← restart crash
08-21 10:55 / 17:10 / 19:11  same StartNotAllowed crash each retry
08-26 00:35:27  DidNotStopInTime → retries crash until at least 21:22
08-28 11:43:22  DidNotStopInTime                                ← last one
09-09 13:00:38  process starts — only because the user tapped the
               pcontrol icon (Launcher touch log precedes it)
```

Between 08-28 11:43 and 09-09 13:00 the app was **not running at all** —
no `TrackerService` log line exists in that window.

### Root cause

1. `TrackerService` declares `android:foregroundServiceType="dataSync"`.
   Android 15+ enforces a hard **6-hour timeout** on `dataSync` FGS
   (`ForegroundServiceDidNotStopInTimeException`).
2. The kill crashes the process; `START_STICKY` restart attempts then hit
   `ForegroundServiceStartNotAllowedException: Time limit already exhausted`
   in `TrackerService.onCreate` (line 76, `startForeground`) — crash loop
   with backoff until the system gives up.
3. The service only comes back when the app is **opened in the foreground**
   (`PROC_STATE_TOP` permits the FGS start). Hence "dead for hours or days,
   resumes by itself" — it was the user opening the phone/app.
4. **Usage is not back-reported** because attribution is purely live: each
   10 s tick adds 10 s for the current foreground app. On restart
   `lastUsageEventQueryTime` is reset to null (60 s bootstrap window) and
   transitions are only used to compute the *current* foreground — nothing
   replays the dead period. The system-side UsageStats data (which Android
   kept collecting) is never re-read.

The HyperOS "Greeze" freeze documented in plan 10 / AGENTS.md is a second,
independent outage source (process alive but frozen). The same backfill
mechanism below also covers intra-process stalls.

## Fix

### Stage 1 — core backfill logic (`:core`, pure JVM, TDD)

New `TimedAppEvent(packageName, eventType, timestampMs)` and
`UsageBackfill`:

- `plan(cursorMs, nowMs, minGapMs, maxWindowMs)` → `Window?` — decide
  whether a persisted tick cursor indicates a reportable gap
  (≥ 2 min, clamped to 7 days).
- `attribute(events, selfPackage, window, zone, silenceCapMs)` →
  `List<Slice(day, subject, seconds)>` — folds UsageEvents transitions over
  the gap, attributing each eventless interval to the then-foreground app:
  - intervals split at local midnight so day keys stay correct;
  - each eventless interval is capped (`silenceCapMs`, default 5 min) so a
    locked/overnight screen is never inflated into hours of usage;
  - the app's own package is never attributed (matches the tick path, which
    prefers accessibility foreground and excludes self);
  - unknown/`null` foreground (background transition) attributes nothing.

### Stage 2 — service fixes

- **FGS type**: manifest becomes `foregroundServiceType="dataSync|specialUse"`
  plus `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "continuous_usage_tracking"` and
  the `FOREGROUND_SERVICE_SPECIAL_USE` permission. At runtime,
  `ServiceCompat.startForeground(..., type)` starts with
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+ (no timeout) and
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` below.
- **Crash-safe `startForeground`**: wrap in try/catch. On failure the
  service stops itself (`stopSelf`) — callers use
  `Context.startForegroundService`, so lingering without a completed
  foreground start would crash the app seconds later via
  RemoteServiceException ("did not then call Service.startForeground").
  The bound accessibility service keeps the process alive, and the next
  app-open/boot/package-replace retries the foreground start.
- **Cursor persistence**: each tick (throttled to 1/min) writes
  `tick_cursor_ms` (wall clock of the attribution window end).
- **Gap backfill**: on service start and whenever a tick detects a stall
  (`now - lastTickAt > 2 min`), replay `queryEvents(cursor, now)` through
  `UsageBackfill` and merge the slices into the app counters via
  `UsageDay.mergeCounter`. Backfilled seconds flow to the server as ordinary
  unsynced deltas.
- Web (domain) usage cannot be backfilled retroactively — domains are only
  readable live via the accessibility service. Documented limitation.
- Tick-path consistency: the event-derived foreground can be pcontrol
  itself (parent viewing the dashboard); exclude self there too.

### Stage 3 — release hygiene

- Bump local default `versionName`/`versionCode` (0.0.5/5 → 0.0.8/8);
  release must be tagged `android-v0.0.8` (CI injects the real values).

## Status

- Stage 1 — **Done** (`UsageBackfill` + `TimedAppEvent`, 19 tests)
- Stage 2 — **Done** (manifest + TrackerService)
- Stage 2b — **Done** (`MY_PACKAGE_REPLACED` restart, 3 Robolectric tests)
- Stage 3 — **Done** (0.0.8/8 defaults)
- Device verification — **Done** (see below)

## Review round 1 findings (local reviewer agent, all verified and fixed)

1. **Blocker — past-day backfill booked under today.** `UsageDay.mergeCounter`
   stamped created rows with `LocalDate.now()`, so a backfill slice for a
   past day landed on today's key; worse, the `@Insert(REPLACE)` upsert
   could wipe today's live-counted row and reset `syncedSeconds` →
   duplicate server events. Fix: `mergeCounter` now takes the caller's day
   explicitly (`UsageDay.mergeCounter(existing, day, …)`); regression test
   added.
2. **Major — leading interval never attributed.** `queryEvents` started
   exactly at `window.startMs`, so the app foreground at outage start
   could not be seeded. Fix: query with a `SEED_LOOKBACK_MS` (6 h)
   lookback; `attribute` already clamps to the window. Phantom seeding of
   an overnight screen-off is bounded by the 5-min silence cap.
3. **Major — `USER_INTERACTION` consumed as a background transition.**
   `AppEvent.MOVE_TO_BACKGROUND = 7` aliased the real
   `UsageEvents.Event.USER_INTERACTION`; every touch cleared the
   reconstructed foreground (and `MOVE_TO_FOREGROUND = 6` aliased
   `SYSTEM_INTERACTION`). The live tick path shared the aliasing (masked by
   the accessibility fallback). Fix: the wrong constants are deleted;
   only real transitions `ACTIVITY_RESUMED (1)` / `ACTIVITY_PAUSED (2)` are
   consumed anywhere (the deprecated MOVE_TO_* names are their aliases).
   Regression tests for types 6/7 added.
4. **Minor — throttled cursor persist.** 1/min throttle allowed ≤ ~70 s of
   replayed overlap after a restart. Fix: the cursor persists on every
   commit (small async write).
5. **Minor — merge/markSynced lost update.** A read-merge-upsert straddling
   the sync path's `markSynced` could REPLACE the row with stale
   `syncedSeconds` and re-send uploaded seconds under a new `eventId`.
   Fix: a `usageCounterMutex` serializes merges and the markSynced loop.

Also added: DST-day midnight split test (America/New_York) and post-window
clamping test.

## Review round 2 (local reviewer) — APPROVE

All five round-1 fixes verified present and semantically right (including a
hand recomputation of the DST case and a deadlock/writer audit of the
mutex). Report-only P2s, addressed where cheap:

- ≤10 s over-count per recovery incident from the "10 s per tick"
  sampling boundary — inherent to the model, accepted.
- Stale "≤60 s cursor-persist lag" documentation — updated (persist is
  per-tick now).
- `UsageStatsAdapter.toAppEvents` is uncalled dead code — left in place
  (harmless: `AppUsagePoller` ignores non-transition types; still the
  pattern example AGENTS.md references).

## Copilot review rounds — addressed

- **Backfill ran inline on the tick coroutine** (startup + stall sites),
  violating the repo's "never block the 10-second tick" convention. Fixed:
  `launchBackfill` runs `maybeBackfill` on its own single-flight coroutine
  (AtomicBoolean guard), like sync and update checks.
- **Cursor advanced before `queryEvents`**: a transient query failure would
  permanently skip the window. Fixed: the cursor is claimed only after a
  successful query, immediately before merging — failed queries retry on
  the next stall/restart; merges stay at-most-once.

## Verification on device (done 2026-09-09, locally signed — same key lineage
as the installed 0.0.7, in-place `adb install -r`, data kept)

- `types=0x40000000` (**specialUse**) in `dumpsys activity services` — the
  6-hour dataSync timeout no longer applies (0.0.7 showed `0x00000001`).
- Reinstalling the APK restarts `TrackerService` by itself via the new
  `MY_PACKAGE_REPLACED` handling (stage 2b).
- Live backfill: force-stop → ~2.5 min of Settings usage → start via the
  app button → logcat shows `Backfilled 153s over a 153s gap`, exactly
  the missed window; synced as ordinary deltas.

### Stage 2b — restart after APK update (found during verification)

An in-place APK update stops all running services and `START_STICKY` does
**not** reschedule them — every update (including auto-update installs)
would leave the tracker dead until the next boot or manual app open.
`BootReceiver` now also handles `ACTION_MY_PACKAGE_REPLACED`; covered by
`BootReceiverTest` (boot, package-replaced, unrelated broadcast).
