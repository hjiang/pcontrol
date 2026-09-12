package com.pcontrol.app

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean
import com.pcontrol.app.update.UpdateCoordinator
import com.pcontrol.app.update.UpdateResult
import com.pcontrol.app.update.UpdateState
import com.pcontrol.core.AppEvent
import com.pcontrol.core.AppUsagePoller
import com.pcontrol.core.BrowserContext
import com.pcontrol.core.PolicyEngine
import com.pcontrol.core.PolicyV2
import com.pcontrol.core.TimedAppEvent
import com.pcontrol.core.UsageBackfill
import com.pcontrol.core.UsageDay
import com.pcontrol.core.UsageAttribution
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.BackfillStateEntity
import com.pcontrol.app.db.UsageCounterEntity
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.UUID

class TrackerService : Service() {

    companion object {
        const val TAG = "TrackerService"
        const val CHANNEL_ID = "pcontrol_tracker"
        const val CHANNEL_ID_UPDATE = "pcontrol_update"
        const val NOTIFICATION_ID = 1
        const val TICK_INTERVAL_MS = 10_000L  // 10 seconds
        const val SYNC_INTERVAL_MS = 60_000L  // 60 seconds

        // Wall-clock cursor of the last committed attribution window,
        // persisted so a restarted (or Greeze-thawed) tracker can replay
        // the UsageStats events of the missed period.
        private const val PREFS_NAME = "pcontrol"
        private const val KEY_TICK_CURSOR_MS = "tick_cursor_ms"

        // Recovery progress is committed in chunks of at most this span
        // (each chunk's counter merges + progress advance commit in one
        // Room transaction), so a mid-recovery process death can never put
        // more than one chunk's data at risk.
        private const val BACKFILL_CHUNK_MS = 60 * 60_000L

        // Detection-debt record: a detected gap is written here BEFORE the
        // Room registration is attempted, so a transient database failure
        // cannot drop the window (the recovery job promotes the debt into
        // the durable backfill_state row).
        private const val KEY_BF_DEBT_START_MS = "backfill_debt_start_ms"
        private const val KEY_BF_DEBT_END_MS = "backfill_debt_end_ms"

        // Backoff when retirement cannot complete because the debt clear
        // could not be persisted (storage trouble) — retried until it
        // succeeds; the durable row stays as the consistency marker.
        private const val RETIRE_RETRY_BACKOFF_MS = 10_000L

        // Retry backoff for the registration write of a detected gap
        // (transient Room failures; the durable debt covers loss in between).
        private const val REGISTRATION_RETRY_BACKOFF_MS = 2_000L

        // Legacy int op id for GET_USAGE_STATS (since API 21). Used via a
        // reflectively resolved checkOpNoThrow(int, int, String) on API 26–28
        // (the String overloads do not exist there), mirroring
        // MainActivity.hasUsageStatsPermission().
        private const val LEGACY_OP_GET_USAGE_STATS = 43
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var tickJob: Job? = null
    private var lastSyncTime = 0L

    // Volatile: written from the tick coroutine and (on the retry re-entry
    // path) from the recovery worker; read every tick. Read-modify-writes
    // take [anchorLock].
    @Volatile
    private var lastUsageEventQueryTime: Long? = null

    /** Serializes the monotonic read/modify/write of
     *  [lastUsageEventQueryTime] between the tick coroutine and the
     *  recovery worker's retry re-entry. Non-suspending, ns-scale. */
    private val anchorLock = Any()

    /** Monotonic anchor update under [anchorLock]: the live-query anchor
     *  only ever moves forward, whichever coroutine writes it (the tick,
     *  a detection, the recovery worker's retry re-entry, or the
     *  [startTicks] seed — which can re-enter via onStartCommand while the
     *  previous tick job is still finishing or a worker is running). A
     *  plain assignment here would let one writer rewind another's newer
     *  value, making the next tick re-query an already-accounted range. */
    private fun advanceAnchorTo(timeMs: Long) {
        synchronized(anchorLock) {
            lastUsageEventQueryTime = maxOf(lastUsageEventQueryTime ?: timeMs, timeMs)
        }
    }
    private var currentForegroundPkg: String? = null

    // Serializes the backfill bookkeeping — pending-row registration and
    // retirement, the detection debt, and the pinned-window queue — among
    // the backfill coroutines (detections and the worker). The live tick
    // cursor is NOT part of this state: commitTick is its single writer and
    // never takes this mutex, so no database I/O here can ever delay the
    // 10-second tick.
    private val backfillMutex = Mutex()

    // Guards the detection-debt preference operations (stage/merge, read,
    // clear) so a detector's staging is atomic against the worker's
    // promotion and clearing. The worker's writeDebt/clearPendingDebt use
    // commit() (blocking disk I/O) under this lock by DESIGN: the commit
    // must be atomic with the read it was computed from — a
    // revalidate-after-commit protocol would miss a detector write that
    // lands between the read and the commit, reintroducing the stale-
    // overwrite data-loss bug. The lock is only contended on the RARE
    // detection path (service start / ≥2-minute stall staging, which is
    // fire-and-forget work on its own coroutine — the 10-second
    // attribution path never takes it: commitTick uses [anchorLock]); a
    // concurrent detection therefore waits at most one flash commit
    // (milliseconds), never on Room I/O (which stays outside the lock).
    private val debtLock = Any()

    // Gap windows detected while a recovery job was already running (the
    // single-flight guard). Pinned (start, end) pairs, drained by the
    // running job before it retires — a freeze that begins during the
    // startup replay is queued, never silently dropped.
    //
    // Every access takes [queueLock] — a plain monitor, never held across a
    // suspension: the recovery worker touches the queue inside its
    // `backfillMutex` sections, and the tick-side detector enqueues into it
    // when a new gap is disjoint from the staged debt (the debt record is a
    // singleton, so overwriting it would discard the still-unpromoted gap).
    private val queueLock = Any()
    private val pendingRecoveryWindows = ArrayDeque<UsageBackfill.Window>()

    /** Guards long-running side work so it never stalls the 10-second tick. */
    private val syncInFlight = AtomicBoolean(false)
    private val updateCheckInFlight = AtomicBoolean(false)
    private val backfillInFlight = AtomicBoolean(false)
    private var ticksWithoutDomain = 0

    // Browser foreground session tracking
    private var browserForegroundPkg: String? = null
    // Tracks the last blocked web domain for strike reset logic
    private var lastBlockedWebSubject: String? = null
    // Tracks the current day for rollover detection
    private var lastDay: String = ""
    private var lastLoggedForegroundCandidates: String? = null
    private var lastLoggedAttributionSkip: String? = null
    // Loop-level heartbeat for stall (freeze-thaw) detection, measured in
    // SystemClock.elapsedRealtime() (monotonic — immune to wall-clock changes)
    private var lastTickAtMs = 0L
    // Serializes counter read-merge-upsert against the sync path's
    // markSynced writes: a merge straddling markSynced would REPLACE the
    // row with a stale syncedSeconds and re-send already-uploaded seconds.
    private val usageCounterMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        blockingCoordinator = BlockingCoordinator(this)
        createNotificationChannels()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTicks()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private lateinit var blockingCoordinator: BlockingCoordinator

    /**
     * Starts the foreground service with a type that has no lifetime cap.
     *
     * `dataSync` was the original type; Android 15+ kills dataSync FGS
     * after 6 hours (`ForegroundServiceDidNotStopInTimeException`) and then
     * rejects restarts with "Time limit already exhausted" until the app is
     * opened in the foreground — the root cause of the multi-day tracker
     * outages diagnosed on HyperOS 3 / Android 16 (plan 15). `specialUse` has
     * no timeout; below API 34 only dataSync exists and no timeout applies.
     *
     * A failure here must never crash the process: the process also hosts
     * the bound accessibility service. Because every caller uses
     * Context.startForegroundService, an unresolved start contract would
     * crash the app anyway via RemoteServiceException ("did not then call
     * Service.startForeground") seconds later — so a failed foreground
     * start stops the service cleanly instead of lingering as a doomed
     * background service. The bound accessibility service keeps the
     * process alive, and the next boot, package replacement, or app-open
     * retries the foreground start.
     */
    private fun startForegroundSafely() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed; stopping service to avoid the FGS contract crash", e)
            stopSelf()
        }
    }

    private fun startTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            // §9: sync immediately on service start, then every 60s.
            // 0 forces the first post-tick sync check to fire right away.
            lastSyncTime = 0L
            // Seed the live-query anchor from the persisted cursor instead
            // of null: the rollback guard in [onTick] only sees this
            // in-memory value, and after a restart it is the persisted
            // cursor that knows how far live attribution actually counted.
            // If the clock was set back while the service was stopped, the
            // seeded anchor sits in the future, the guard keeps live
            // queries paused until the clock catches up, and the first tick
            // cannot bootstrap [now-60s, now] over wall-clock the cursor
            // already covers. (Fresh install: cursor 0 → unchanged
            // 60-second bootstrap; normal restart: the first query replays
            // transitions since the last commit, ending on the correct
            // current foreground.)
            //
            // The seed goes through the monotonic helper under [anchorLock]:
            // onStartCommand can re-enter while the previous tick job is
            // still finishing its last commitTick or while a recovery worker
            // is running, and a plain assignment would rewind the in-memory
            // anchor to the older persisted value.
            val persistedCursor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_TICK_CURSOR_MS, 0L)
            if (persistedCursor > 0L) advanceAnchorTo(persistedCursor)
            lastTickAtMs = 0L

            // The process may have just been resurrected after hours or days
            // (crash, FGS timeout kill, boot, force-stop). Replay what the
            // system UsageStats recorded while we were gone — on its own
            // coroutine: a multi-day replay must never stall the tick loop.
            launchBackfill(System.currentTimeMillis())

            // Seed the current-foreground STATE for the first live ticks.
            // The anchor advance keeps the first tick's query disjoint from
            // the recovery window, so the transitions inside the recovered
            // range are not replayed into the foreground state machine;
            // without this seed, a restart whose accessibility probe is
            // unavailable and that is not followed by a new transition would
            // record no live usage until the next app switch. STATE-ONLY:
            // nothing is credited from this query (the tick credit is the
            // flat per-tick sample), so its overlap with the recovered range
            // is the same documented ≤1-tick residual as the startup-race
            // coordination. One bounded query per service start.
            if (currentForegroundPkg == null) {
                try {
                    lastUsageEventQueryTime?.let { anchor ->
                        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                        val seedEvents = mutableListOf<AppEvent>()
                        val ev = android.app.usage.UsageEvents.Event()
                        val query = usm.queryEvents(maxOf(0L, anchor - 300_000L), anchor)
                        while (query.hasNextEvent()) {
                            query.getNextEvent(ev)
                            val pkg = ev.packageName ?: continue
                            when (ev.eventType) {
                                AppEvent.ACTIVITY_RESUMED,
                                AppEvent.ACTIVITY_PAUSED -> seedEvents.add(AppEvent(pkg, ev.eventType))
                            }
                        }
                        currentForegroundPkg = AppUsagePoller
                            .updateForegroundPackage(null, seedEvents)
                            ?.takeUnless { it == packageName }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "foreground state seed failed", e)
                }
            }

            val updateState = UpdateState(this@TrackerService)

            while (true) {
                // Stall detection measures ELAPSED time (monotonic): a
                // wall-clock change (NTP correction, manual set) must not
                // fabricate a multi-minute stall — advancing recovery through
                // future wall-clock time — or suppress a real one.
                val tickStartElapsed = SystemClock.elapsedRealtime()
                if (lastTickAtMs > 0 && tickStartElapsed - lastTickAtMs >= UsageBackfill.MIN_GAP_MS) {
                    // The loop stalled with the process alive (e.g. a HyperOS
                    // Greeze freeze-thaw). The exact last attribution end is
                    // still in memory (survives freezes, unlike a restart) —
                    // pass it as the floor so nothing already counted is
                    // replayed. Launched off-loop like sync/update checks so
                    // ticks resume immediately.
                    launchBackfill(System.currentTimeMillis(), lastUsageEventQueryTime ?: 0L)
                }
                lastTickAtMs = tickStartElapsed

                try {
                    onTick()
                } catch (e: Exception) {
                    Log.w(TAG, "onTick exception", e)
                }

                val now = System.currentTimeMillis()

                // Sync every 60 seconds on a separate coroutine. Network I/O
                // must never pause usage attribution or enforcement ticks.
                if (now - lastSyncTime >= SYNC_INTERVAL_MS &&
                    syncInFlight.compareAndSet(false, true)
                ) {
                    lastSyncTime = now
                    scope.launch {
                        try {
                            onSync()
                        } catch (e: Exception) {
                            Log.w(TAG, "onSync exception", e)
                        } finally {
                            syncInFlight.set(false)
                        }
                    }
                }

                // Update check every 24 hours, independent of sync.
                // Launched on a separate coroutine so the blocking download
                // never stalls the 10-second usage-tracking tick loop.
                // Guarded by AtomicBoolean so only one check runs at a time.
                if (updateState.autoUpdateEnabled &&
                    now - updateState.lastUpdateCheckMs >= UpdateState.UPDATE_CHECK_INTERVAL_MS &&
                    updateCheckInFlight.compareAndSet(false, true)
                ) {
                    scope.launch {
                        try {
                            onUpdateCheck()
                        } catch (e: Exception) {
                            Log.w(TAG, "Update check failed", e)
                        } finally {
                            updateCheckInFlight.set(false)
                        }
                    }
                }

                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun onUpdateCheck() {
        val coordinator = UpdateCoordinator(
            context = this,
            versionName = BuildConfig.VERSION_NAME
        )
        val result = coordinator.runOnce()

        when (result) {
            is UpdateResult.INSTALL_TRIGGERED -> {
                postUpdateNotification("Install dialog shown for update")
            }
            is UpdateResult.SIGNATURE_MISMATCH -> {
                postUpdateNotification("Update available (manual install required)")
            }
            is UpdateResult.VERSION_ERROR -> {
                Log.w(TAG, "Version parse error during update check")
            }
            is UpdateResult.INSTALL_FAILED -> {
                postUpdateNotification("Update downloaded but install failed")
            }
            else -> { /* silent — already logged by coordinator */ }
        }
    }

    private fun postUpdateNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_UPDATE)
            .setContentTitle("pcontrol update")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }

    private suspend fun onTick() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val endTime = System.currentTimeMillis()
        // Wall-clock rollback guard: the monotonic cursor can sit ahead of a
        // rolled-back clock, and that wall-clock range was already counted —
        // attributing now would add duplicate seconds for the same wall-clock
        // range (the accessibility blocking path enforces independently of
        // this loop). Counting resumes with a fresh bootstrap window once the
        // clock catches up.
        lastUsageEventQueryTime?.let { lastQuery ->
            if (lastQuery > endTime) {
                Log.w(TAG, "clock rolled back (cursor $lastQuery > now $endTime); tick skipped")
                return
            }
        }

        // Bootstrap from a short window, then consume each event only once.
        // Foreground events are transitions, not periodic heartbeats, so a
        // rolling window would forget an app that stays open for over a minute.
        val startTime = lastUsageEventQueryTime
            ?.takeIf { it <= endTime }
            ?: (endTime - 60_000)

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val eventList = mutableListOf<AppEvent>()

        // One reusable event; getNextEvent overwrites it each iteration.
        val event = android.app.usage.UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                AppEvent.ACTIVITY_RESUMED,
                AppEvent.ACTIVITY_PAUSED -> eventList.add(AppEvent(pkg, event.eventType))
            }
        }
        // UsageEvents does not implement Closeable; resources freed by GC

        val previousForegroundPkg = currentForegroundPkg
        val eventForegroundPkg = AppUsagePoller.updateForegroundPackage(
            previousForegroundPackage = previousForegroundPkg,
            events = eventList
        )
            // pcontrol's own dashboard is usage of the parent tool, not of the
            // child — never attribute it (the accessibility path already
            // excludes self via takeUnless below).
            .takeUnless { it == packageName }
        // HyperOS can omit a UsageEvent when a task is resumed from Recents.
        // The bound accessibility service can still read the active root, so
        // prefer it for the periodic attribution/enforcement fallback.
        val rawAccessibilityPkg = withTimeoutOrNull(1_000L) {
            accessibilityForegroundPackage()
        }
        // An accessibility result of pcontrol itself is AUTHORITATIVE: the
        // parent's dashboard is foreground and NO child app is in use — do
        // not fall back to the (potentially stale) event-derived app, which
        // would attribute dashboard time to it. Only when the accessibility
        // probe produced nothing at all does the event-derived state apply.
        val accessibilitySelf = rawAccessibilityPkg == packageName
        val accessibilityPkg = rawAccessibilityPkg?.takeUnless { it == packageName }
        val foregroundPkg = if (accessibilitySelf) null else accessibilityPkg ?: eventForegroundPkg
        val candidates = "accessibility=$rawAccessibilityPkg " +
            "events=$eventForegroundPkg selected=$foregroundPkg"
        if (candidates != lastLoggedForegroundCandidates) {
            lastLoggedForegroundCandidates = candidates
            Log.i(TAG, "Foreground candidates: $candidates")
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val screenInteractive = powerManager.isInteractive
        val keyguardLocked = keyguardManager.isKeyguardLocked
        if (!UsageAttribution.shouldAttribute(screenInteractive, keyguardLocked)) {
            // Do not attribute a retained foreground package while the display
            // is off or the keyguard is locked: nothing is genuinely in use.
            // Browser domain state is not valid across this boundary.
            val skipState = "interactive=$screenInteractive keyguardLocked=$keyguardLocked"
            if (skipState != lastLoggedAttributionSkip) {
                lastLoggedAttributionSkip = skipState
                Log.i(TAG, "Attribution skipped: $skipState")
            }
            val skip = UsageAttribution.skipTransition(
                previousForegroundPkg = previousForegroundPkg,
                foregroundPkg = foregroundPkg,
                isKnownBrowser = BrowserRegistry::isKnownBrowser,
            )
            skip.browsersToClear.forEach { BrowserAccessibilityService.domainCache.clear(it) }
            browserForegroundPkg = null
            ticksWithoutDomain = 0
            commitTick(skip.nextForegroundPkg, endTime)
            return
        }

        if (foregroundPkg == null) {
            previousForegroundPkg?.let { pkg ->
                // Browser left foreground
                if (BrowserRegistry.isKnownBrowser(pkg)) {
                    BrowserAccessibilityService.domainCache.clear(pkg)
                    browserForegroundPkg = null
                    ticksWithoutDomain = 0
                }
            }
            commitTick(foregroundPkg, endTime)
            return
        }

        val prevPkg = previousForegroundPkg

        // Handle browser foreground transitions
        if (prevPkg != foregroundPkg) {
            // Previous browser left foreground — clear its cache
            prevPkg?.let { pkg ->
                if (BrowserRegistry.isKnownBrowser(pkg)) {
                    BrowserAccessibilityService.domainCache.clear(pkg)
                }
            }
            // New browser entered foreground
            if (BrowserRegistry.isKnownBrowser(foregroundPkg)) {
                browserForegroundPkg = foregroundPkg
                ticksWithoutDomain = 0
            } else {
                browserForegroundPkg = null
                ticksWithoutDomain = 0
            }
        }

        val zone = ZoneId.systemDefault()
        val day = UsageDay.currentKey(zone)

        // Record app usage
        val db = AppDatabase.getInstance(this)
        val label = blockingCoordinator.resolveLabel(foregroundPkg)

        incrementCounter(db, day, "app", foregroundPkg, label)

        // Record and evaluate web usage if in a known browser with a readable domain
        var currentDomain: String? = null
        if (BrowserRegistry.isKnownBrowser(foregroundPkg)) {
            currentDomain = BrowserAccessibilityService.domainCache.get(foregroundPkg)

            if (currentDomain != null) {
                incrementCounter(db, day, "web", currentDomain, currentDomain)
                ticksWithoutDomain = 0
            } else {
                ticksWithoutDomain++
            }
        } else {
            ticksWithoutDomain = 0
        }

        // Counters are durable at this point. Commit the cursor before
        // best-effort enforcement so an enforcement failure cannot replay and
        // double-count this tick's usage.
        commitTick(foregroundPkg, endTime)

        // ── Day rollover: clean up old warned entries ──────────────
        val dbForRollover = AppDatabase.getInstance(this)
        if (day != lastDay) {
            if (lastDay.isNotEmpty()) {
                try {
                    kotlinx.coroutines.runBlocking {
                        dbForRollover.warnedSubjectDao().deleteOtherDays(day)
                    }
                } catch (_: Exception) {}
            }
            lastDay = day
        }

        // ── Enforcement (PolicyEngine + Enforcer) ───────────────────
        try {
            runEnforcement(day, foregroundPkg, currentDomain)
        } catch (e: Exception) {
            // Usage was already recorded and cursor-committed above. Keep the
            // next tick from replaying the same interval and double-counting.
            Log.w(TAG, "runEnforcement exception", e)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun accessibilityForegroundPackage(): String? =
        suspendCancellableCoroutine { continuation ->
            BrowserAccessibilityService.requestActiveForeground { pkg ->
                val resumeToken = continuation.tryResume(pkg)
                if (resumeToken != null) continuation.completeResume(resumeToken)
            }
        }

    private suspend fun runEnforcement(day: String, pkg: String, domain: String?) {
        // Policy evaluation is shared with accessibility-triggered enforcement.
        // Presentation is deliberately delegated to the accessibility service,
        // whose controller rejects stale foreground generations and owns the
        // TYPE_ACCESSIBILITY_OVERLAY lifecycle.
        val evaluation = blockingCoordinator.evaluateForeground(
            pkg = pkg,
            domain = domain,
            ticksWithoutDomain = ticksWithoutDomain
        )
        BrowserAccessibilityService.submitTrackerEvaluation(pkg, domain, evaluation)

        // Reset BLOCK_WEB strikes when the web verdict/subject changes.
        val webSubject = domain ?: pkg
        if (evaluation.webVerdict != com.pcontrol.core.Verdict.BLOCK_WEB && lastBlockedWebSubject != null) {
            Enforcer.webBlockStrikes.reset(lastBlockedWebSubject!!)
            lastBlockedWebSubject = null
        } else if (evaluation.webVerdict == com.pcontrol.core.Verdict.BLOCK_WEB) {
            if (lastBlockedWebSubject != null && lastBlockedWebSubject != webSubject) {
                Enforcer.webBlockStrikes.reset(lastBlockedWebSubject!!)
            }
            lastBlockedWebSubject = webSubject
        }
    }



    /**
     * Commits a tick's attribution cursor: updates in-memory foreground /
     * query state and persists the wall-clock cursor so a later restart or
     * freeze-thaw knows exactly where live counting stopped. Persisting
     * every tick (a small SharedPreferences write) minimizes restart
     * replay overlap; apply() is async, so sudden process death can still
     * lose the newest write — the residual overlap is bounded by one tick.
     *
     * The cursor is MONOTONIC: both the in-memory query anchor and the
     * persisted frontier only ever move forward (max of the previous value
     * and this tick's end), so a wall-clock rollback (NTP correction,
     * manual clock change) can never rewind the frontier over
     * already-counted usage — live queries idle (the inverted-window guard
     * in [onTick] bootstraps a fresh window) until the clock catches up.
     * Both updates run under [anchorLock]: commitTick is the tick
     * coroutine's writer, but onStartCommand can start a replacement tick
     * job while the cancelled one is still finishing its last commit, and
     * two unsynchronized read/modify/apply sequences could then apply out
     * of order, letting the OLDER endTime become the persisted frontier
     * (replay/duplicate backfill after a restart). apply() only updates
     * the in-memory map synchronously and queues the disk write, so the
     * monitor is held for microseconds.
     */
    private suspend fun commitTick(foregroundPkg: String?, endTime: Long) {
        currentForegroundPkg = foregroundPkg
        synchronized(anchorLock) {
            lastUsageEventQueryTime = maxOf(lastUsageEventQueryTime ?: endTime, endTime)
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putLong(
                    KEY_TICK_CURSOR_MS,
                    maxOf(prefs.getLong(KEY_TICK_CURSOR_MS, 0L), endTime)
                )
                .apply()
        }
    }

    /**
     * Requests recovery of the gap ending at [nowMs], starting at the live
     * frontier (never before [minStartMs], which the freeze-thaw path sets
     * to the in-memory last query end so nothing already counted is
     * replayed). Fire-and-forget: the tick coroutine only snapshots the
     * frontier, stages the gap (apply() — non-blocking, immediately
     * visible), and coordinates the first live query with the recovery end;
     * every durable write (the debt's commit(), the Room registration, the
     * recovery itself) happens on the recovery worker, so the 10-second
     * tick never waits on storage.
     *
     * A worker pass is started whenever durable recovery work may exist — a
     * staged gap, an unpromoted detection debt, or a pending row with
     * remaining work — even when no new live gap is planned: a restart
     * whose frontier is recent must still resume an interrupted recovery
     * instead of stranding it.
     *
     * Durable handoff: the staged gap is promoted into the pending row
     * (Room — durable) by [runRecovery] → [promoteDebt]. The residual loss
     * window is the worker scheduling latency plus the staged apply()'s
     * async flush (the same accepted class as commitTick's own cursor
     * apply()); if the process dies inside it, the next detection re-plans
     * the gap from the live frontier.
     */
    private fun launchBackfill(nowMs: Long, minStartMs: Long = 0L) {
        // Snapshot the frontier + coordinate the first live query with the
        // recovery end: both are in-memory operations, safe on the tick
        // loop. The recovery covers (frontier, now]; advancing the live
        // event-query anchor to `now` keeps the first live tick's query
        // disjoint from the recovery window (bootstrapping 60 s back would
        // double-count those transitions after a restart).
        val frontierMs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_TICK_CURSOR_MS, 0L)
        val window = planWindow(frontierMs, nowMs, minStartMs)
        if (window != null) {
            synchronized(debtLock) {
                val staged = pendingDebt()
                when {
                    // Merge an overlapping staged debt (only possible when no
                    // live tick has committed since it was staged — the
                    // frontier would sit past its end otherwise — so the
                    // merged span is entirely uncounted).
                    staged != null &&
                        window.startMs < staged.endMs && window.endMs > staged.startMs ->
                        stageDebt(
                            mergedWindow(
                                UsageBackfill.Window(staged.startMs, staged.endMs),
                                window
                            )
                        )
                    // DISJOINT staged debt: keep it — the debt record is a
                    // singleton, and staging over it would discard a gap the
                    // worker has not promoted yet (a second detection can
                    // outrun promotion when Room is slow or failing). The new
                    // gap rides the in-memory queue instead — the same
                    // documented residual as every queued window: a process
                    // death before it is claimed loses it until the next
                    // detection re-plans it.
                    staged != null -> synchronized(queueLock) {
                        enqueueClamped(window, staged.endMs)
                    }
                    // Nothing staged: the debt slot is free.
                    else -> stageDebt(window)
                }
            }
            // Coordinate the first live query with the recovery end. The
            // anchor is also advanced by commitTick on the tick coroutine,
            // and this function re-enters from the worker's retry path, so
            // the monotonic max runs under [anchorLock] — the
            // read/modify/write must not interleave and rewind the anchor.
            advanceAnchorTo(nowMs)
        }
        scope.launch {
            var ownsGuard = false
            try {
                backfillMutex.withLock {
                    val dao = AppDatabase.getInstance(this@TrackerService).backfillStateDao()
                    val window = planWindow(frontierMs, nowMs, minStartMs)
                    // Debt read → merge → durable publication is ONE debtLock
                    // critical section: a detector staging concurrently (an
                    // apply(), a few ms) cannot interleave a newer record
                    // between this read and this write — a stale `owed`
                    // computed from the old read would otherwise overwrite
                    // the newer gap. Nothing here suspends (prefs + queue
                    // ops only), so a concurrent detector waits on the
                    // monitor at most for one commit(), never on Room.
                    val debt = synchronized(debtLock) {
                        val current = pendingDebt()
                        if (window != null) when {
                            // Disjoint from everything owed: queue (clamped)
                            // and leave the debt untouched — the debt stays
                            // the single durable publication that
                            // [promoteDebt] installs as the pending row.
                            current != null && window.startMs >= current.endMs -> {
                                synchronized(queueLock) {
                                    enqueueClamped(window, current.endMs)
                                }
                                current
                            }
                            // Overlapping (or no) staged debt: the merged
                            // span is the record — overlap implies no live
                            // tick has committed in between, so it is
                            // entirely uncounted.
                            else -> {
                                val owed = if (current != null) {
                                    mergedWindow(
                                        UsageBackfill.Window(current.startMs, current.endMs),
                                        window
                                    )
                                } else {
                                    window
                                }
                                if (writeDebt(owed)) {
                                    owed
                                } else {
                                    // The durable record could not be
                                    // persisted: what is actually on disk
                                    // (the apply()-staged copy, if any) stays
                                    // the record; the next detection re-plans
                                    // the gap from the live frontier and
                                    // retries (logged for diagnosis).
                                    Log.w(TAG, "detection debt commit failed; recovery deferred")
                                    current
                                }
                            }
                        } else {
                            current
                        }
                    }
                    // Start/retain the worker based on ACTUAL persisted or
                    // staged state — the debt, the queue, and the pending
                    // row — never on the planned window: if writeDebt's
                    // commit() failed, window != null would still start a
                    // worker that finds nothing and retires, silently
                    // dropping the only recovery request on a continuously
                    // healthy service (detections are rare). window != null
                    // always implies debt != null here (the detection side
                    // stages the debt before this coroutine runs), so the
                    // debt check subsumes it.
                    // A COMPLETED row whose end is ahead of the persisted
                    // cursor still counts as work: it is the recovered-
                    // through marker, and a worker must retire it once the
                    // live cursor catches up (otherwise a restart leaves the
                    // marker stranded and live queries resume from inside
                    // the recovered window's documentation trail forever).
                    val row = dao.get()
                    val cursor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getLong(KEY_TICK_CURSOR_MS, 0L)
                    val hasDurableWork = debt != null ||
                        synchronized(queueLock) { pendingRecoveryWindows.isNotEmpty() } ||
                        (row != null && row.endMs > 0L &&
                            (row.progressMs < row.endMs || cursor < row.endMs))
                    if (hasDurableWork) {
                        ownsGuard = backfillInFlight.compareAndSet(false, true)
                    }
                }
                if (!ownsGuard) return@launch
                var failures = 0
                while (true) {
                    try {
                        runRecovery()
                        return@launch  // retired; guard released inside the lock
                    } catch (e: Exception) {
                        failures++
                        Log.w(TAG, "backfill failed (attempt $failures)", e)
                        // The durable pending state is untouched and will
                        // retry. ANY durable recovery work — queued windows,
                        // a pending row with remaining work, or an unpromoted
                        // detection debt — keeps the worker alive (with
                        // backoff): releasing the guard would strand it until
                        // the next detection.
                        var drained = false
                        backfillMutex.withLock {
                            val dao = AppDatabase.getInstance(this@TrackerService).backfillStateDao()
                            val row = dao.get()
                            val debt = synchronized(debtLock) { pendingDebt() }
                            drained = synchronized(queueLock) { pendingRecoveryWindows.isNotEmpty() } ||
                                (row != null && row.endMs > 0L && row.progressMs < row.endMs) ||
                                debt != null
                            if (!drained) backfillInFlight.set(false)
                        }
                        if (!drained) return@launch
                        delay(RETIRE_RETRY_BACKOFF_MS)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "backfill failed", e)
                // A registration/recovery failure must not strand durable
                // work (a staged debt, a pending row with remaining work, or
                // queued windows): keep a worker retrying with backoff while
                // anything is owed. The check runs whether or not this
                // coroutine owned the guard — a registration exception can
                // occur before the CAS (e.g. dao.get() right after writeDebt
                // succeeded), and the staged debt must still be retried. If
                // the durable state cannot even be read, assume work remains
                // and retry: the re-entered pass re-evaluates with fresh
                // state, and a persistently failing database yields a slow
                // retry loop, never silent abandonment.
                var retry = true
                try {
                    backfillMutex.withLock {
                        val dao = AppDatabase.getInstance(this@TrackerService).backfillStateDao()
                        val row = dao.get()
                        val debt = synchronized(debtLock) { pendingDebt() }
                        retry = synchronized(queueLock) { pendingRecoveryWindows.isNotEmpty() } ||
                            (row != null && row.endMs > 0L && row.progressMs < row.endMs) ||
                            debt != null
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "backfill retry check failed; assuming work remains", e2)
                }
                // Release the guard BEFORE re-entering: the retry's own CAS
                // must be able to acquire it — a still-held flag would fail
                // compareAndSet, return, and wedge single-flight (and every
                // future detection) permanently.
                if (ownsGuard) backfillInFlight.set(false)
                if (retry) {
                    delay(REGISTRATION_RETRY_BACKOFF_MS)
                    launchBackfill(nowMs, minStartMs)
                }
            }
        }
    }

    /** Plans the recovery window for a detected gap, or null when it is
     *  not reportable (fresh install, sub-threshold gap, clock skew).
     *  [frontierMs] is the live-cursor snapshot taken at detection time. */
    private fun planWindow(
        frontierMs: Long,
        nowMs: Long,
        minStartMs: Long
    ): UsageBackfill.Window? =
        UsageBackfill.plan(maxOf(frontierMs, minStartMs), nowMs)

    private data class PendingDebt(val startMs: Long, val endMs: Long)

    private fun pendingDebt(): PendingDebt? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val end = prefs.getLong(KEY_BF_DEBT_END_MS, 0L)
        if (end <= 0L) return null
        return PendingDebt(prefs.getLong(KEY_BF_DEBT_START_MS, 0L), end)
    }

    /** Stages the detected gap as the detection debt with apply() — non-
     *  blocking on the tick loop and immediately visible; the recovery
     *  worker upgrades it durably (writeDebt's commit() and the Room row)
     *  before relying on it. The residual apply()-flush loss window is the
     *  same accepted class as commitTick's own cursor apply(). */
    private fun stageDebt(window: UsageBackfill.Window) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_BF_DEBT_START_MS, window.startMs)
            .putLong(KEY_BF_DEBT_END_MS, window.endMs)
            .apply()
    }

    /** Synchronously records the detection debt (commit(), not apply(): the
     *  debt is the crash-fallback for a failed Room registration, so it must
     *  be on disk the moment registration proceeds). Returns whether it was
     *  persisted — false only downgrades the crash-fallback. */
    private fun writeDebt(window: UsageBackfill.Window): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_BF_DEBT_START_MS, window.startMs)
            .putLong(KEY_BF_DEBT_END_MS, window.endMs)
            .commit()

    /** Synchronously removes the detection-debt record; false when the
     *  removal could not be persisted — the caller must then keep whatever
     *  durable state assumes the debt is gone, or a stale debt could
     *  resurrect an already-recovered window on the next restart. */
    private fun clearPendingDebt(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(KEY_BF_DEBT_START_MS)
            .remove(KEY_BF_DEBT_END_MS)
            .commit()

    /** Removes the detection-debt record only when it still matches [debt].
     *  The read that produced [debt] and the clear span suspending Room
     *  work, and a detector may stage a newer (merged or disjoint-kept)
     *  record in between — clearing unconditionally would wipe the newer
     *  gap from its only durable record. A superseded debt is left intact
     *  for the next pass. Returns whether no record remains that assumes
     *  the caller's promotion (cleared, or superseded by a newer one). */
    private fun clearPendingDebtIfUnchanged(debt: PendingDebt): Boolean =
        synchronized(debtLock) {
            val current = pendingDebt()
            if (current == null || current != debt) true else clearPendingDebt()
        }

    /** The end of the still-owed region: the durable Room window if one is
     *  active, otherwise the unpromoted detection debt. */
    private fun pendingEndMs(
        row: com.pcontrol.app.db.BackfillStateEntity?,
        debt: PendingDebt?
    ): Long = maxOf(row?.endMs ?: 0L, debt?.endMs ?: 0L)

    /** Queues [window] for the running job, clamped so it cannot overlap
     *  anything still owed: neither the durable pending window (whose
     *  progress advances chunk by chunk), the windows already queued ahead
     *  of it — two detections with a stale frontier would otherwise enqueue
     *  overlapping ranges — nor (for the detector path) the staged debt.
     *  Fully-subsumed requests are dropped. Safe from the tick coroutine:
     *  the queue accesses take [queueLock] (never held across suspension). */
    private fun enqueueClamped(window: UsageBackfill.Window?, owedThroughMs: Long) {
        synchronized(queueLock) {
            var w = window ?: return
            var owed = owedThroughMs
            pendingRecoveryWindows.lastOrNull()?.let { owed = maxOf(owed, it.endMs) }
            if (owed > w.startMs) {
                w = UsageBackfill.Window(owed, maxOf(w.endMs, owed))
            }
            if (w.endMs > w.startMs) pendingRecoveryWindows.addLast(w)
        }
    }

    /** The union span of two overlapping windows. Valid only for overlapping
     *  inputs: an overlap implies no live tick has committed inside the
     *  union (the frontier would sit past the older end otherwise), so the
     *  merged span is entirely uncounted. */
    private fun mergedWindow(
        a: UsageBackfill.Window,
        b: UsageBackfill.Window
    ): UsageBackfill.Window =
        UsageBackfill.Window(minOf(a.startMs, b.startMs), maxOf(a.endMs, b.endMs))

    /**
     * Promotes an unpromoted detection-debt record into the durable Room
     * row. If the promotion write fails, the debt record survives and is
     * retried on the next job pass or detection — a transient database
     * failure can never permanently drop a detected outage window. The
     * debt is removed synchronously and only after a successful promotion;
     * if that removal cannot be persisted, it is retried on a later pass
     * (a stale debt with an active row that subsumes it is harmless).
     */
    private suspend fun promoteDebt() {
        backfillMutex.withLock {
            val dao = AppDatabase.getInstance(this@TrackerService).backfillStateDao()
            val row = dao.get()
            // Debt read/clear is serialized against the detector's staging
            // via [debtLock] (both are fast preference operations; the Room
            // write in between stays outside the detector's reach, and the
            // clear below is conditional on the debt being unchanged since
            // it was read).
            val debt = synchronized(debtLock) { pendingDebt() }
            when {
                debt == null -> return
                // Row still owes work: a debt staged while this row was
                // mid-recovery starts at/after the row's end (detectors clamp
                // against the owed frontier), so its uncovered tail is queued
                // behind the row — otherwise retirement would wipe it. A debt
                // fully inside the row's remaining work is already covered
                // and dropped.
                row != null && row.endMs > 0L && row.progressMs < row.endMs -> {
                    if (debt.endMs > row.endMs) {
                        // Queue via the clamping helper, not a bare add: it
                        // clamps the tail against the latest queued window's
                        // end too, so a later detection that EXTENDED the
                        // debt yields a disjoint tail instead of a second,
                        // overlapping copy of an already-queued range (an
                        // exact-pair check would append both and replay the
                        // overlap twice). Idempotency is preserved: when the
                        // debt clear below cannot be persisted, the
                        // re-staged identical tail clamps to an empty window
                        // and is dropped.
                        val tail = UsageBackfill.Window(
                            maxOf(debt.startMs, row.endMs), debt.endMs
                        )
                        enqueueClamped(tail, row.endMs)
                    }
                    // Conditional: the Room write above suspended, and a
                    // detector may have staged a NEWER debt meanwhile —
                    // clearing unconditionally would wipe its only record.
                    synchronized(debtLock) { clearPendingDebtIfUnchanged(debt) }
                }
                else -> {
                    // Promote the debt as the next window, never moving the
                    // progress backwards past what the completed row already
                    // recovered.
                    val start = maxOf(debt.startMs, row?.endMs ?: 0L)
                    val end = maxOf(debt.endMs, row?.endMs ?: 0L)
                    if (end > start) {
                        dao.set(BackfillStateEntity(0, start, end))
                    }
                    // Conditional for the same reason as above: a debt staged
                    // while dao.set() was in flight supersedes this snapshot
                    // and must survive for the next pass.
                    synchronized(debtLock) { clearPendingDebtIfUnchanged(debt) }
                }
            }
        }
    }

    /**
     * Processes the durable pending recovery window, then any gap windows
     * that were queued while it ran, then retires. The retire step clears
     * the pending state and releases the single-flight guard inside
     * [backfillMutex] together with the final work check, so a concurrent
     * detector either registers before the check (the job loops and drains
     * it) or acquires the guard afterwards (and starts a fresh job).
     */
    private suspend fun runRecovery() {
        while (true) {
            promoteDebt()
            val handled = recoverPendingWindow()
            var retire = false
            // A pass that could not run (UsageStats access unavailable)
            // retries after a backoff; the pending row is kept deliberately.
            var retryLater = !handled
            backfillMutex.withLock {
                val dao = AppDatabase.getInstance(this).backfillStateDao()
                val row = dao.get()
                when {
                    // The pass could not run (UsageStats access currently
                    // unavailable): leave the active row AND the queue exactly
                    // as they are — claiming `next` here would overwrite the
                    // active row's unrecovered remainder and lose it. Retry
                    // after a backoff.
                    !handled -> Unit
                    synchronized(queueLock) { pendingRecoveryWindows.isNotEmpty() } -> {
                        // Peek first and remove only after the durable write
                        // succeeded: a transient Room failure then leaves the
                        // window queued for the retry instead of losing it.
                        // Queue accesses take [queueLock], never held across
                        // the suspending Room call.
                        val next = synchronized(queueLock) {
                            pendingRecoveryWindows.firstOrNull()
                        }
                        if (next != null) {
                            dao.set(BackfillStateEntity(0, next.startMs, next.endMs))
                            synchronized(queueLock) {
                                // Same single-worker pass; a detector can only
                                // add, so the head we peeked is still the head.
                                if (pendingRecoveryWindows.firstOrNull() == next) {
                                    pendingRecoveryWindows.removeFirstOrNull()
                                }
                            }
                        }
                    }
                    // No queued window — but a late registrar may have
                    // published a NEW pending row while we were finishing the
                    // previous one (registration is serialized with this
                    // check): process it instead of retiring. The just-
                    // finished row does NOT count as new work: it still has
                    // endMs > 0 but no remaining work (progress == end) —
                    // treating it as work would hot-loop here forever.
                    row != null && row.endMs > 0L && row.progressMs < row.endMs -> Unit
                    else -> {
                        // Nothing owed by the row. Retire — but only once the
                        // live tick cursor has caught up with the recovered
                        // frontier (clearing earlier would let a restart with
                        // a stale cursor re-register and replay this window),
                        // and only after confirming the detection debt does
                        // not extend beyond it: a debt staged for a newer gap
                        // is converted into the next claimed window instead
                        // of being wiped at retirement. The debt's read, tail
                        // conversion, and clear are ONE debtLock critical
                        // section: a gap the detector stages concurrently is
                        // either fully observed (queued, then cleared) or
                        // staged after the clear (it survives as the next
                        // pass's debt) — it can never be read and then wiped
                        // by a separate, later clear.
                        val recoveredEnd = row?.endMs ?: 0L
                        val frontier = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getLong(KEY_TICK_CURSOR_MS, 0L)
                        synchronized(debtLock) {
                            val debt = pendingDebt()
                            if (debt != null && debt.endMs > recoveredEnd) {
                                // Route through the clamping helper so the
                                // queued-windows-stay-disjoint invariant is
                                // structural: the queue is empty in this
                                // branch (checked in this same critical
                                // section), so the clamp is a no-op today —
                                // but it guards any future mutation.
                                enqueueClamped(
                                    UsageBackfill.Window(
                                        maxOf(debt.startMs, recoveredEnd), debt.endMs
                                    ),
                                    recoveredEnd
                                )
                                // The queued tail needs ANOTHER pass of THIS
                                // worker: retiring now would leave it in the
                                // in-memory queue with no runner, and a
                                // continuously healthy service may never
                                // detect again. A failed clear additionally
                                // keeps the completed row as the retry marker
                                // (retiring with the debt still persisted
                                // would let a restart re-promote the FULL
                                // debt range and re-count the part the row
                                // already recovered; the re-queued tail
                                // clamps to a no-op once already recovered,
                                // so nothing replays twice in the meantime).
                                retryLater = true
                                if (!clearPendingDebt()) {
                                    Log.w(TAG, "detection debt clear not persisted; retire retried")
                                }
                            } else {
                                // No debt, or one fully covered by the
                                // recovered frontier (clearable: the row
                                // stays as the recovered-through marker
                                // until the live cursor catches up, so a
                                // restart cannot replay it).
                                retryLater =
                                    frontier < recoveredEnd || !clearPendingDebt()
                            }
                        }
                        if (!retryLater) {
                            dao.clear()
                            backfillInFlight.set(false)
                            retire = true
                        }
                    }
                }
            }
            when {
                retire -> return
                retryLater -> delay(RETIRE_RETRY_BACKOFF_MS)
            }
        }
    }

    /**
     * Replays one pending recovery window — (progressMs, endMs] from the
     * durable backfill state — covering process death (crash, FGS kill,
     * boot) and freeze-thaw stalls. Web (domain) usage cannot be recovered
     * this way — domains are only readable live via the accessibility
     * service.
     *
     * Durability: each chunk's counter merges and its progress advance
     * commit in ONE Room transaction. A crash or failed write rolls both
     * back, so the window retries from the failed chunk with no lost
     * slices and no double-counted ones — recovery progress is always
     * tied to successfully written counters. (The mutex keeps the
     * transaction from straddling the sync path's markSynced writes.)
     *
     * Returns true when the pass needs no retry (window processed, or no
     * work) and false when it must be retried later (UsageStats access
     * currently unavailable — the pending row is deliberately kept).
     */
    private suspend fun recoverPendingWindow(): Boolean {
        val db = AppDatabase.getInstance(this)
        val state = db.backfillStateDao().get() ?: return true
        if (state.endMs <= 0L || state.endMs <= state.progressMs) return true
        // The window was threshold-vetted when it was registered; a retry
        // must replay every remaining progressMs < endMs — including a
        // short tail left behind by already-committed chunks, which the
        // minimum-gap check would wrongly drop. Only the 7-day clamp
        // applies here.
        val window = UsageBackfill.Window(
            maxOf(state.progressMs, state.endMs - UsageBackfill.MAX_WINDOW_MS),
            state.endMs
        )

        // Without the PACKAGE_USAGE_STATS app-op, queryEvents() silently
        // returns empty data — treating that as a successful recovery would
        // advance the frontier over an unrecovered window and permanently
        // discard it. Keep the row pending; it retries on the next pass,
        // stall, or restart, and recovers once access is granted again.
        if (!hasUsageStatsAccess()) {
            Log.w(TAG, "usage-stats access unavailable; recovery deferred")
            return false
        }

        val usageStatsManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Recompute from before the progress frontier (with the usual seed
        // lookback) so the silence-cap clock keeps its true interval starts:
        // an eventless interval whose allowance was consumed by already-
        // committed chunks must not receive a fresh one after a retry. Only
        // time at/after the durable progress frontier is counted.
        val replayFrom = maxOf(0L, window.startMs - UsageBackfill.SEED_LOOKBACK_MS)
        val usageEvents = usageStatsManager.queryEvents(
            maxOf(0L, replayFrom),
            window.endMs
        )
        val timed = mutableListOf<TimedAppEvent>()
        // One reusable event; getNextEvent overwrites it each iteration —
        // multi-day windows can yield thousands of events.
        val event = android.app.usage.UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                // Only real transitions (1/2). UsageEvents type 7 is
                // USER_INTERACTION — treating it as a background transition
                // zeroes attribution after every touch.
                AppEvent.ACTIVITY_RESUMED,
                AppEvent.ACTIVITY_PAUSED ->
                    timed.add(TimedAppEvent(pkg, event.eventType, event.timeStamp))
            }
        }
        // UsageEvents does not implement Closeable; resources freed by GC

        val chunks = UsageBackfill.attributeChunked(
            events = timed,
            selfPackage = packageName,
            window = UsageBackfill.Window(replayFrom, window.endMs),
            chunkMs = BACKFILL_CHUNK_MS,
            zone = ZoneId.systemDefault(),
            countFromMs = window.startMs
        )

        val dao = db.backfillStateDao()
        var backfilledSeconds = 0L
        for (chunk in chunks) {
            val labelled = chunk.slices.map { slice ->
                slice to blockingCoordinator.resolveLabel(slice.subject)
            }
            if (labelled.isEmpty()) {
                dao.advanceProgress(chunk.window.endMs)
                continue
            }
            // The counter mutex is taken PER CHUNK (acquired and released
            // once per ~1 h slice set inside the loop below), so a tick
            // arriving mid-recovery waits at most ONE chunk's transaction —
            // a handful of read+upsert row pairs, tens of milliseconds
            // against the 10-second tick interval — not the whole window.
            // Holding it across the transaction is required: the chunk's
            // counter merges and its advanceProgress must commit atomically
            // (crash ⇒ both roll back ⇒ no lost/double-counted slices), and
            // the same mutex serializes against the sync path's markSynced
            // writes (a merge straddling markSynced would resend already-
            // uploaded seconds). Decoupling them would reintroduce that
            // lost-update class.
            usageCounterMutex.withLock {
                db.withTransaction {
                    for ((slice, label) in labelled) {
                        mergeCounterLocked(
                            db = db,
                            day = slice.day,
                            kind = "app",
                            subject = slice.subject,
                            label = label,
                            increment = slice.seconds
                        )
                    }
                    dao.advanceProgress(chunk.window.endMs)
                }
            }
            backfilledSeconds += chunk.slices.sumOf { it.seconds.toLong() }
        }
        val gapSeconds = (window.endMs - window.startMs) / 1000
        Log.i(TAG, "Backfilled ${backfilledSeconds}s over a ${gapSeconds}s gap")
        return true
    }

    /**
     * Whether the system will actually serve UsageStats queries. Without
     * the PACKAGE_USAGE_STATS app-op, queryEvents() silently returns empty
     * data, which must never be mistaken for "nothing to recover" — the
     * caller keeps the pending row and retries until access is granted.
     *
     * Mirrors MainActivity.hasUsageStatsPermission()'s API-safety pattern:
     * `unsafeCheckOpNoThrow` on API 29+, and the legacy int-id
     * `checkOpNoThrow(int, int, String)` overload resolved reflectively
     * below (the String overloads do not exist on API 26–28, and a
     * NoSuchMethodError is an Error, not an Exception). Any probe failure
     * returns false: deferring recovery is recoverable, advancing the
     * frontier over unrecovered data is not.
     */
    private fun hasUsageStatsAccess(): Boolean = try {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            // Legacy int-id overload (op id 43, since API 21) — the String
            // overloads are not present on API 26–28.
            val check = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            check.invoke(
                appOps,
                LEGACY_OP_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            ) as Int
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        Log.w(TAG, "usage-stats access could not be confirmed; recovery deferred", t)
        false
    }

    private suspend fun incrementCounter(
        db: AppDatabase,
        day: String,
        kind: String,
        subject: String,
        label: String
    ) {
        mergeCounter(db, day, kind, subject, label, increment = 10)  // 10-second tick
    }

    /** Read-merge-upsert for one (day, kind, subject) counter row. */
    private suspend fun mergeCounter(
        db: AppDatabase,
        day: String,
        kind: String,
        subject: String,
        label: String,
        increment: Int
    ) {
        usageCounterMutex.withLock {
            mergeCounterLocked(db, day, kind, subject, label, increment)
        }
    }

    /**
     * [mergeCounter] without the mutex — for callers that already hold
     * [usageCounterMutex] (the backfill transaction) or need many rows in
     * one critical section. Not reentrant: never call while holding the
     * mutex.
     */
    private suspend fun mergeCounterLocked(
        db: AppDatabase,
        day: String,
        kind: String,
        subject: String,
        label: String,
        increment: Int
    ) {
        val dao = db.usageCounterDao()
        val existing = dao.get(day, kind, subject)
            val merged = UsageDay.mergeCounter(
                existing = existing?.let {
                    com.pcontrol.core.UsageCounter(
                        day = it.day,
                        kind = it.kind,
                        subject = it.subject,
                        label = it.label,
                        seconds = it.seconds,
                        syncedSeconds = it.syncedSeconds
                    )
                },
                day = day,
                kind = kind,
                subject = subject,
                label = label,
                increment = increment
            )
            dao.upsert(
                UsageCounterEntity(
                    day = merged.day,
                    kind = merged.kind,
                    subject = merged.subject,
                    label = merged.label,
                    seconds = merged.seconds,
                    syncedSeconds = merged.syncedSeconds
                )
            )
    }

    private suspend fun onSync() {
        val db = AppDatabase.getInstance(this)
        val unsynced = db.usageCounterDao().getUnsynced()

        // Snapshot the seconds value BEFORE the network call so we can
        // restore exactly what was sent even if a tick fires mid-request (§9).
        val snapshotSeconds = unsynced.associate { c ->
            Triple(c.day, c.kind, c.subject) to c.seconds
        }

        // Build sync request from unsynced deltas
        val events = unsynced.map { counter ->
            val delta = counter.seconds - counter.syncedSeconds
            SyncEvent(
                eventId = UUID.randomUUID().toString(),
                kind = counter.kind,
                subject = counter.subject,
                label = counter.label,
                day = counter.day,
                startedAt = java.time.Instant.now().toString(),
                durationSeconds = delta
            )
        }

        val prefs = SecretPrefs.getInstance(this)
        val serverUrl = prefs.getServerUrl()
        val deviceToken = prefs.getDeviceToken()
        if (serverUrl.isEmpty() || deviceToken.isEmpty()) return

        val client = SyncClient(serverUrl, deviceToken)
        val cachedPolicyVersion = getSharedPreferences("pcontrol", MODE_PRIVATE).getInt("policy_version", 0)

        val batteryStatus = BatteryStatusReader(this).read()

        val request = SyncRequest(
            deviceTime = java.time.Instant.now().toString(),
            policyVersion = cachedPolicyVersion,
            events = events,
            batteryPercent = batteryStatus?.percent,
            batteryCharging = batteryStatus?.charging
        )

        val response = client.sync(request)
        if (response == null) return // Network error, retry next sync

        // Mark synced counters using the snapshot values (not current seconds)
        usageCounterMutex.withLock {
            for (counter in unsynced) {
                val sent = snapshotSeconds[Triple(counter.day, counter.kind, counter.subject)] ?: counter.seconds
                db.usageCounterDao().markSynced(counter.day, counter.kind, counter.subject, sent)
            }
        }

        // Process policy update
        if (response.policy != null) {
            val policyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            db.cachedPolicyDao().upsert(
                com.pcontrol.app.db.CachedPolicyEntity(
                    version = response.policy.version,
                    json = policyJson.encodeToString(PolicyResponse.serializer(), response.policy)
                )
            )
            getSharedPreferences("pcontrol", MODE_PRIVATE).edit().putInt("policy_version", response.policy.version).apply()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackerChannel = NotificationChannel(
                CHANNEL_ID,
                "pcontrol tracker",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(trackerChannel)

            val updateChannel = NotificationChannel(
                CHANNEL_ID_UPDATE,
                "pcontrol updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when app updates are available"
            }
            manager.createNotificationChannel(updateChannel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("pcontrol")
            .setContentText("Monitoring usage…")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
