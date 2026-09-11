package com.pcontrol.app

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
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var tickJob: Job? = null
    private var lastSyncTime = 0L
    private var lastUsageEventQueryTime: Long? = null
    private var currentForegroundPkg: String? = null

    // Serializes the live tick cursor (KEY_TICK_CURSOR_MS) and the pending
    // recovery state between the 10-second tick loop (commitTick), gap
    // detection, and the backfill coroutine — a detection must never read a
    // stale frontier, and no writer but commitTick ever touches the live
    // cursor, so it can never move backwards.
    private val cursorMutex = Mutex()

    // Gap windows detected while a recovery job was already running (the
    // single-flight guard). Pinned (start, end) pairs, drained by the
    // running job before it retires — a freeze that begins during the
    // startup replay is queued, never silently dropped.
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
    // Loop-level heartbeat for stall (freeze-thaw) detection
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
            lastUsageEventQueryTime = null
            lastTickAtMs = 0L

            // The process may have just been resurrected after hours or days
            // (crash, FGS timeout kill, boot, force-stop). Replay what the
            // system UsageStats recorded while we were gone — on its own
            // coroutine: a multi-day replay must never stall the tick loop.
            launchBackfill(System.currentTimeMillis())

            val updateState = UpdateState(this@TrackerService)

            while (true) {
                val tickStart = System.currentTimeMillis()
                if (lastTickAtMs > 0 && tickStart - lastTickAtMs >= UsageBackfill.MIN_GAP_MS) {
                    // The loop stalled with the process alive (e.g. a HyperOS
                    // Greeze freeze-thaw). The exact last attribution end is
                    // still in memory (survives freezes, unlike a restart) —
                    // pass it as the floor so nothing already counted is
                    // replayed. Launched off-loop like sync/update checks so
                    // ticks resume immediately.
                    launchBackfill(tickStart, lastUsageEventQueryTime ?: 0L)
                }
                lastTickAtMs = tickStart

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

        // Bootstrap from a short window, then consume each event only once.
        // Foreground events are transitions, not periodic heartbeats, so a
        // rolling window would forget an app that stays open for over a minute.
        val endTime = System.currentTimeMillis()
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
        val accessibilityPkg = rawAccessibilityPkg?.takeUnless { it == packageName }
        val foregroundPkg = accessibilityPkg ?: eventForegroundPkg
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
     * Serialized on [cursorMutex]: this is the ONLY writer of the live
     * cursor, and gap detection reads it under the same lock, so the
     * frontier is monotonic and detection windows can never overlap
     * already-counted ticks. A pending usage-backfill recovery lives in a
     * separate durable state (Room `backfill_state`) that this path cannot
     * overwrite — a failed or in-flight backfill keeps its frontier.
     */
    private suspend fun commitTick(foregroundPkg: String?, endTime: Long) {
        currentForegroundPkg = foregroundPkg
        lastUsageEventQueryTime = endTime
        cursorMutex.withLock {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_TICK_CURSOR_MS, endTime)
                .apply()
        }
    }

    /**
     * Requests recovery of the gap ending at [nowMs], starting at the live
     * frontier (never before [minStartMs], which the freeze-thaw path sets
     * to the in-memory last query end so nothing already counted is
     * replayed). Runs on its own coroutine — the UsageStats query and
     * counter merges, potentially large after a multi-day outage, must
     * never stall the 10-second tick loop.
     *
     * The gap becomes the durable pending recovery window (Room
     * `backfill_state`), which survives process death and is invisible to
     * [commitTick]: live ticks can no longer erase an outage frontier, so
     * a failed query or merge retries at the next stall/restart instead of
     * permanently losing the missed usage.
     */
    private suspend fun launchBackfill(nowMs: Long, minStartMs: Long = 0L) {
        if (!tryAcquireBackfill(nowMs, minStartMs)) return
        scope.launch {
            try {
                runRecovery()
            } catch (e: Exception) {
                Log.w(TAG, "backfill failed", e)
                // The durable pending window is untouched — the next stall
                // or restart retries it.
                backfillInFlight.set(false)
            }
        }
    }

    /**
     * Registers a detected gap and reports whether the caller must launch
     * the recovery job (the single-flight discipline).
     *
     * When a job is already running, the request is NOT dropped: it is
     * queued as a pinned window that the running job processes before it
     * retires — otherwise a freeze detected during the startup replay
     * would be skipped forever. Detection runs under [cursorMutex], so the
     * window start is the exact un-counted frontier and can never overlap
     * a concurrent tick commit.
     */
    private suspend fun tryAcquireBackfill(nowMs: Long, minStartMs: Long): Boolean {
        if (!backfillInFlight.compareAndSet(false, true)) {
            // Loser path: no guard is ours to release — just try to queue.
            var takeover = false
            try {
                cursorMutex.withLock {
                    planBackfillWindow(nowMs, minStartMs)?.let { pendingRecoveryWindows.addLast(it) }
                    // The running job may have retired between our failed CAS
                    // and this critical section (it releases the guard inside
                    // the same lock as its final queue check). If so, take
                    // over and drain what we just queued; if the flag is still
                    // set, the running job is guaranteed to poll again.
                    takeover = backfillInFlight.compareAndSet(false, true)
                }
            } catch (e: Exception) {
                // Registration must never kill the tick loop; the next
                // stall/restart re-detects the gap.
                Log.w(TAG, "backfill registration failed", e)
            }
            return takeover
        }
        // Winner path: we hold the single-flight guard — release it on any
        // failure so future detections are never permanently blocked.
        try {
            cursorMutex.withLock {
                val dao = AppDatabase.getInstance(this).backfillStateDao()
                val hasPending = (dao.get()?.endMs ?: 0L) > 0L
                val window = planBackfillWindow(nowMs, minStartMs)
                when {
                    // A previous process died mid-recovery: the durable window
                    // stays authoritative; any newly detected gap queues behind it.
                    hasPending -> window?.let { pendingRecoveryWindows.addLast(it) }
                    window != null -> dao.set(BackfillStateEntity(0, window.startMs, window.endMs))
                    else -> {
                        // No reportable gap — release the guard we just took.
                        backfillInFlight.set(false)
                        return false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "backfill registration failed", e)
            backfillInFlight.set(false)
            return false
        }
        return true
    }

    /** Plans the recovery window for a detected gap, or null when it is
     *  not reportable (fresh install, sub-threshold gap, clock skew).
     *  Must be called under [cursorMutex]. */
    private fun planBackfillWindow(nowMs: Long, minStartMs: Long): UsageBackfill.Window? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return UsageBackfill.plan(
            maxOf(prefs.getLong(KEY_TICK_CURSOR_MS, 0L), minStartMs),
            nowMs
        )
    }

    /**
     * Processes the durable pending recovery window, then any gap windows
     * that were queued while it ran, then retires. The retire step clears
     * the pending state and releases the single-flight guard inside
     * [cursorMutex] together with the final queue check, so a concurrent
     * detector either queues before the check (the job loops and drains
     * it) or acquires the guard afterwards (and starts a fresh job).
     */
    private suspend fun runRecovery() {
        while (true) {
            recoverPendingWindow()
            var retire = false
            cursorMutex.withLock {
                val dao = AppDatabase.getInstance(this).backfillStateDao()
                val next = pendingRecoveryWindows.removeFirstOrNull()
                if (next != null) {
                    dao.set(BackfillStateEntity(0, next.startMs, next.endMs))
                } else {
                    dao.clear()
                    backfillInFlight.set(false)
                    retire = true
                }
            }
            if (retire) return
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
     */
    private suspend fun recoverPendingWindow() {
        val db = AppDatabase.getInstance(this)
        val state = db.backfillStateDao().get() ?: return
        if (state.endMs <= 0L) return
        // Sub-MIN_GAP residue (or a fully clamped window): nothing worth
        // replaying; the retire step clears the state.
        val window = UsageBackfill.plan(state.progressMs, state.endMs) ?: return

        val usageStatsManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Query with a lookback so the app that was foreground when tracking
        // stopped seeds the replay — the leading interval is otherwise
        // unattributable. UsageBackfill clamps and only counts in-window time.
        val usageEvents = usageStatsManager.queryEvents(
            maxOf(0L, window.startMs - UsageBackfill.SEED_LOOKBACK_MS),
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
            window = window,
            chunkMs = BACKFILL_CHUNK_MS,
            zone = ZoneId.systemDefault()
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
