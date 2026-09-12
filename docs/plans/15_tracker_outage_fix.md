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

- `plan(cursorMs, nowMs)` → `Window?` — decide whether a persisted tick
  cursor indicates a reportable gap; thresholds are the fixed constants
  `MIN_GAP_MS` (≥ 2 min) and `MAX_WINDOW_MS` (clamped to 7 days).
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
  plus `PROPERTY_SPECIAL_USE_FGS_SUBTYPE =
  "continuous_usage_tracking_for_parental_controls"` (exact manifest value)
  and the `FOREGROUND_SERVICE_SPECIAL_USE` permission. At runtime,
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
- **Cursor persistence**: every committed tick writes
  `tick_cursor_ms` (wall clock of the attribution window end) — except
  when the tick knew no foreground app AND UsageStats access is
  unconfirmed. Not merely "credited nothing": a locked-screen tick credits
  nothing but still knows the foreground state, and holding the cursor
  back there would stage locked-screen time as a recovery window and
  replay it against the locked-screen attribution rule. `commitTick` then
  holds the cursor back so the unattributed stretch stays recoverable (the
  overlong-gap guard stages it as a recovery window, replayed once access
  is granted).
- **Gap backfill**: on service start and whenever a tick detects a stall
  (`now - lastTickAt > 2 min`), the gap becomes a durable **pending
  recovery window** (Room `backfill_state`) that live ticks cannot
  overwrite; the replay runs `queryEvents` through `UsageBackfill`
  chunk-by-chunk (~1 h), committing each chunk's counter merges and its
  progress advance in one Room transaction, and merges the slices into
  the app counters via `UsageDay.mergeCounter`. Backfilled seconds flow
  to the server as ordinary unsynced deltas. (See the hardening section
  below for the failure-mode rationale.)
- Web (domain) usage cannot be backfilled retroactively — domains are only
  readable live via the accessibility service. Documented limitation.
- Locked-screen time inside a recovery window: the replay sees only app
  transitions, not screen/keyguard state (those UsageEvents are API 30+ and
  would need a gated replay), so an eventless interval that spans a device
  lock can contribute up to the 5-minute silence cap of locked time. The
  live path attributes nothing while the keyguard is locked; recovery stays
  bounded by the same cap that bounds all recovery phantom attribution.
  Documented limitation.
- Tick-path consistency: the event-derived foreground can be pcontrol
  itself (parent viewing the dashboard); exclude self there too.

### Stage 3 — release hygiene

- Bump local default `versionName`/`versionCode` (0.0.5/5 → 0.0.8/8);
  release must be tagged `android-v0.0.8` (CI injects the real values).

## Status

- Stage 1 — **Done** (`UsageBackfill` + `TimedAppEvent`, 27 tests)
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
  `launchBackfill` runs the replay on its own single-flight coroutine
  (AtomicBoolean guard), like sync and update checks.
- **Cursor advanced before `queryEvents`**: a transient query failure would
  permanently skip the window. Fixed in the rounds below: recovery progress
  moved to its own durable state that ticks cannot overwrite.

## Copilot review rounds — recovery-state hardening (final round)

The single `tick_cursor_ms` frontier conflated "live counting reached here"
with "recovery has reconstructed through here", which let live ticks erase
an outage frontier and let a mid-merge death drop a whole window. Redesigned
as a durable **pending recovery window** (Room `backfill_state`, schema v3:
`progressMs` / `endMs`, 0 = none), kept strictly separate from the live
tick cursor:

- **Live ticks can no longer erase a pending recovery** (`commitTick` never
  touches `backfill_state`). A failed query/merge leaves the frontier
  intact; the next stall/restart retries the window.
- **Progress is tied to written data**: each chunk (~1 h) of the window is
  attributed via `UsageBackfill.attributeChunked`, and the chunk's counter
  merges + its `advanceProgress` commit in ONE Room transaction — a crash
  rolls both back (no lost slices, no double-counted ones). The old
  claim-the-whole-window-before-merging is gone.
- **The cursor is monotonic and serialized**: `commitTick` writes
  `max(previous, endTime)` (in-memory anchor too) under `anchorLock`, and
  recovery retirement's durable cursor pin writes the same preference
  under the same lock, so neither a racing detection nor a wall-clock
  rollback can move the frontier backwards. Persistence is gated: when a
  tick knew no foreground app AND UsageStats access is unconfirmed, the
  cursor is held back so the stretch becomes a recovery window once access
  returns (a locked-screen tick credits nothing but still knows the
  foreground state, so it advances the cursor).
- **Registration is serialized with publication** under `backfillMutex`:
  a detector always sees previously published windows and either clamps
  behind them or publishes its own gap as the pending row when nothing is
  owed — a request can never queue an un-clamped duplicate of another
  detector's gap, and queued windows are clamped against the latest
  queued end. `backfillMutex` never involves `commitTick`, so backfill
  database I/O cannot delay the tick.
- **Detection during an in-flight recovery is queued, not dropped**:
  requests arriving while the single-flight guard is held are appended as
  pinned windows and drained by the running job before it retires (the
  guard is released inside the final queue check's critical section, so a
  queue entry can never be orphaned by the exit race). A freeze that starts
  during the startup replay is therefore recovered.
- **Queued/new windows are clamped against the durable pending end**: a
  detection whose start precedes the still-owed region (e.g. a restart
  whose live cursor has not yet caught up with committed recovery
  progress) is clamped to that end, so it can never replay
  already-merged slices.
- **Detections are durable before Room**: the gap is recorded as a
  detection-debt record in SharedPreferences before the Room registration
  is attempted; the job promotes the debt into `backfill_state`. A failed
  Room registration therefore retries instead of dropping the window, and
  registration itself runs off the 10-second loop (only an in-memory
  frontier snapshot happens on the tick coroutine).
- **Retries keep silence-cap and tail semantics**: a retried window is
  replayed from before the durable progress frontier with only time at/
  after the frontier counted (`attributeChunked(countFromMs = …)`), so an
  interval whose 5-min allowance was consumed by committed chunks gets no
  fresh allowance; and the retry replays every remaining
  `progressMs < endMs` (no minimum-gap check on retry — a 1-minute tail
  left by committed chunks is still recovered).
- Silence-cap semantics survive chunking: the cap is measured from the
  true interval start across chunk boundaries (a 45-min eventless stretch
  still contributes ≤ 5 min total, never 5 min per chunk); sub-second
  remainders carry across chunks so per-chunk seconds sum exactly to the
  single-shot conversion.
- **Sub-second remainders carry across chunks** so per-chunk seconds sum
  exactly to the single-shot conversion; and a stray `ACTIVITY_PAUSED` for
  a non-current app no longer restarts the silence-cap clock — the interval
  only closes when the attribution state actually changes.
- **The live tick cursor is monotonic**: `commitTick` writes
  `max(previous, endTime)` for both the persisted frontier and the
  in-memory query anchor, so a wall-clock rollback can never rewind the
  frontier over already-counted usage (live queries idle until the clock
  catches up).
- **Registration is serialized with publication** under one mutex
  (`backfillMutex`): a detector sees every previously published window and
  publishes its own gap as the pending row when nothing is owed yet, so a
  request can never queue an un-clamped duplicate of the winner's gap.
  Queued windows are additionally clamped against the latest queued end.
- **Detection-debt writes and clears are synchronous** (`commit()`):
  the debt must be on disk before registration proceeds, and retirement
  only clears the durable row after a verified debt clear — a stale debt
  can never resurrect a recovered window. Retirement retries with backoff
  if the clear cannot be persisted.
- **The tick loop never waits on backfill I/O**: `backfillMutex` never
  involves the tick cursor; its two writers (`commitTick` and recovery
  retirement's durable cursor pin) are serialized with each other under
  `anchorLock` (monotonic `max(previous, value)`), and all Room access
  happens in backfill coroutines only.
- **Detection stages the gap before returning**: `launchBackfill` records
  the detected gap as the prefs debt (apply() — immediately visible,
  non-blocking on the tick) before it returns; the recovery worker upgrades
  that record durably (commit()) and into the Room row. The residual — a
  process death between staging and the worker's durable write (scheduling
  + flush latency, normally ms) — loses the gap until the next detection
  re-plans it from the live frontier. An overlapping unpromoted debt is
  merged (only possible when no live tick has committed in between), a
  disjoint one is queued instead of overwriting the staged record (the
  debt is a singleton), and the registration write retries in place
  (2 s backoff, retried for as long as durable work remains) on transient
  Room failures.
- **Clock rollback skips ticks**: the monotonic cursor can sit ahead of a
  rolled-back clock; such ticks are skipped entirely (that wall-clock range
  was already counted) instead of double-counting via a bootstrapped
  window; counting resumes once the clock catches up.
- **Claim is peek-then-remove** (the queued window is installed durably
  before it leaves the queue), and the failure path keeps the worker alive
  while ANY durable work remains (queued windows or a pending row with
  `progressMs < endMs`) — retrying with backoff instead of stranding it
  until the next detection.
- **Progress never jumps past an unrecovered queued window**: promoting a
  debt to the row (or extending the row at retirement) that would raise
  `progressMs` to/past a queued window's end — or overlap its span —
  enqueues the window durably behind the queue instead, so the claim
  step's "fully covered" erasure can never silently drop a detected
  outage. The decision is a pure seam (`BackfillPromotion` in `:core`,
  unit-tested) over row end, debt window, and queued windows.
- **The single-flight guard is released atomically with the final work
  check**: the retire arm re-checks queue AND detection debt under
  `backfillMutex` — the same mutex the detector's durable-work check and
  CAS use — so a detector either registers before the check (the job
  loops and drains it) or acquires the guard after the release; durable
  work can never be left with no worker.
- **Retirement is gated on the live cursor catching up** and on remaining
  work: the claim/retire check distinguishes a newly published row
  (`progressMs < endMs` — process it) from the just-finished row
  (`progressMs == endMs` — no hot loop), and only clears the row once
  `tick_cursor_ms ≥ recovered end`, so a restart with a stale cursor can
  never re-register and replay a recovered window; until then the
  completed row stays as the recovered-through marker.
- **Recovery defers when UsageStats access is unavailable**: without the
  PACKAGE_USAGE_STATS app-op, queryEvents silently returns empty data —
  that is no longer mistaken for a successful recovery; the pending row
  is kept and the pass retried until access is granted. The worker also
  stays alive (with backoff) until queued windows are drained instead of
  releasing the guard after a bounded number of failures.
- Residual (documented, accepted): disjoint detection gaps are journaled
  durably at detection (apply() — same async-flush class as the tick
  cursor) and re-ingested on restart, so the remaining loss window is the
  asynchronous journal/debt flush plus worker scheduling (normally ms)
  between a detection and its durable upgrade; recovery progress (Room
  row + detection debt) is always durable.

## Verification on device (done 2026-09-09, locally signed dev build —
see lineage note)

**Signing lineage (corrected):** this verification ran against the
locally signed dev build (dev keystore per plan 06) installed over a
locally signed predecessor on the dev device, in-place `adb install -r`,
data kept. It does **not** attest the CI-signed release artifact: the
dev key and the CI keystore are different keys (AGENTS.md signing
invariant), and a locally built APK cannot update a CI-signed install in
place — verified earlier, non-destructively (signature mismatch). Release
verification rides the `android-v0.0.8` tag → CI APK → auto-update path,
where signature continuity is guaranteed by the single CI keystore.

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
