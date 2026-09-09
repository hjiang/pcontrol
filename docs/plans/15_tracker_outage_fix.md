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
- **Crash-safe `startForeground`**: wrap in try/catch. A failure logs and
  degrades to running as a background service instead of crashing the
  process (which also kills the bound accessibility service). The next
  app-open/boot starts it properly.
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

- Stage 1 — **Done** (`UsageBackfill` + `TimedAppEvent`, 12 tests)
- Stage 2 — **Done** (manifest + TrackerService)
- Stage 3 — **Done** (0.0.8/8 defaults)

## Verification on device (after installing the fixed APK)

- `adb logcat | grep -E "TrackerService|ForegroundService"` shows no
  `DidNotStopInTime` after >6 h of uptime;
- kill the process (`adb shell am force-stop` … after boot) or let Greeze
  freeze it — next tick logs `backfilled Ns over gap`;
- server dashboard shows the previously missing hours.

Manual install note: the CI-signed release is required for auto-update
lineage; a locally signed APK needs a manual install (signature mismatch).
