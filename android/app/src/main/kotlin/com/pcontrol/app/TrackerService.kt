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
import com.pcontrol.app.db.UsageCounterEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
        private const val CURSOR_PERSIST_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var tickJob: Job? = null
    private var lastSyncTime = 0L
    private var lastUsageEventQueryTime: Long? = null
    private var currentForegroundPkg: String? = null

    /** Guards long-running side work so it never stalls the 10-second tick. */
    private val syncInFlight = AtomicBoolean(false)
    private val updateCheckInFlight = AtomicBoolean(false)
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
    private var lastCursorPersistMs = 0L

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
     * the bound accessibility service. Degrade to a background service and
     * let the next app-open or boot restore foreground state.
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
            Log.w(TAG, "startForeground failed; running as background service", e)
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
            // system UsageStats recorded while we were gone.
            try {
                maybeBackfill(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "startup backfill failed", e)
            }

            val updateState = UpdateState(this@TrackerService)

            while (true) {
                val tickStart = System.currentTimeMillis()
                if (lastTickAtMs > 0 && tickStart - lastTickAtMs >= UsageBackfill.MIN_GAP_MS) {
                    // The loop stalled with the process alive (e.g. a HyperOS
                    // Greeze freeze-thaw). The exact last attribution end is
                    // still in memory (survives freezes, unlike a restart) —
                    // use it as the floor so nothing already counted is
                    // replayed.
                    try {
                        maybeBackfill(tickStart, lastUsageEventQueryTime ?: 0L)
                    } catch (e: Exception) {
                        Log.w(TAG, "stall backfill failed", e)
                    }
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

        while (usageEvents.hasNextEvent()) {
            val event = android.app.usage.UsageEvents.Event()
            usageEvents.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                AppEvent.ACTIVITY_RESUMED,
                AppEvent.ACTIVITY_PAUSED,
                AppEvent.MOVE_TO_FOREGROUND,
                AppEvent.MOVE_TO_BACKGROUND -> eventList.add(AppEvent(pkg, event.eventType))
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
     * query state and persists the wall-clock cursor (throttled) so a later
     * restart or freeze-thaw knows exactly where live counting stopped.
     */
    private fun commitTick(foregroundPkg: String?, endTime: Long) {
        currentForegroundPkg = foregroundPkg
        lastUsageEventQueryTime = endTime
        val now = System.currentTimeMillis()
        if (now - lastCursorPersistMs >= CURSOR_PERSIST_INTERVAL_MS) {
            lastCursorPersistMs = now
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_TICK_CURSOR_MS, endTime)
                .apply()
        }
    }

    /**
     * Replays the system UsageStats transitions for the period covered by
     * the persisted tick cursor and attributes it to per-day app counters.
     * Covers process death (crash, FGS kill, boot) and freeze-thaw stalls.
     * Web (domain) usage cannot be recovered this way — domains are only
     * readable live via the accessibility service.
     */
    private suspend fun maybeBackfill(nowMs: Long, minStartMs: Long = 0L) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val cursor = maxOf(prefs.getLong(KEY_TICK_CURSOR_MS, 0L), minStartMs)
        val window = UsageBackfill.plan(cursor, nowMs) ?: return

        // Claim the window before merging so a mid-merge crash cannot
        // double-count it on the next attempt (at-most-once semantics).
        prefs.edit().putLong(KEY_TICK_CURSOR_MS, window.endMs).apply()
        lastCursorPersistMs = System.currentTimeMillis()

        val usageStatsManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usageStatsManager.queryEvents(window.startMs, window.endMs)
        val timed = mutableListOf<TimedAppEvent>()
        while (usageEvents.hasNextEvent()) {
            val event = android.app.usage.UsageEvents.Event()
            usageEvents.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                AppEvent.ACTIVITY_RESUMED,
                AppEvent.ACTIVITY_PAUSED,
                AppEvent.MOVE_TO_FOREGROUND,
                AppEvent.MOVE_TO_BACKGROUND ->
                    timed.add(TimedAppEvent(pkg, event.eventType, event.timeStamp))
            }
        }
        // UsageEvents does not implement Closeable; resources freed by GC

        val slices = UsageBackfill.attribute(
            events = timed,
            selfPackage = packageName,
            window = window,
            zone = ZoneId.systemDefault()
        )
        if (slices.isEmpty()) return

        val db = AppDatabase.getInstance(this)
        for (slice in slices) {
            mergeCounter(
                db = db,
                day = slice.day,
                kind = "app",
                subject = slice.subject,
                label = blockingCoordinator.resolveLabel(slice.subject),
                increment = slice.seconds
            )
        }
        val gapSeconds = (window.endMs - window.startMs) / 1000
        val backfilledSeconds = slices.sumOf { it.seconds }
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
        for (counter in unsynced) {
            val sent = snapshotSeconds[Triple(counter.day, counter.kind, counter.subject)] ?: counter.seconds
            db.usageCounterDao().markSynced(counter.day, counter.kind, counter.subject, sent)
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
