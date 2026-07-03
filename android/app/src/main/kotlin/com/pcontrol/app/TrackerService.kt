package com.pcontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pcontrol.core.AppEvent
import com.pcontrol.core.AppUsagePoller
import com.pcontrol.core.DomainParser
import com.pcontrol.core.UsageDay
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.UsageCounterEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

class TrackerService : Service() {

    companion object {
        const val CHANNEL_ID = "pcontrol_tracker"
        const val NOTIFICATION_ID = 1
        const val TICK_INTERVAL_MS = 10_000L  // 10 seconds
        const val SYNC_INTERVAL_MS = 60_000L  // 60 seconds
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var tickJob: Job? = null
    private var lastSyncTime = 0L
    private var currentForegroundPkg: String? = null
    private var ticksWithoutDomain = 0

    // Browser foreground session tracking
    private var browserForegroundPkg: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTicks()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tickJob?.cancel()
        super.onDestroy()
    }

    private fun startTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            lastSyncTime = System.currentTimeMillis()

            while (true) {
                try {
                    onTick()
                } catch (e: Exception) {
                    // Log and continue
                }

                // Sync every 60 seconds
                val now = System.currentTimeMillis()
                if (now - lastSyncTime >= SYNC_INTERVAL_MS) {
                    try {
                        onSync()
                    } catch (e: Exception) {
                        // Log and continue
                    }
                    lastSyncTime = now
                }

                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private suspend fun onTick() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Query events from the last 60 seconds
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60_000

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val eventList = mutableListOf<AppEvent>()

        while (usageEvents.hasNextEvent()) {
            val event = android.app.usage.UsageEvents.Event()
            usageEvents.getNextEvent(event)
            val pkg = event.packageName ?: continue
            eventList.add(AppEvent(pkg, event.eventType))
        }
        // UsageEvents does not implement Closeable; resources freed by GC

        val foregroundPkg = AppUsagePoller.extractForegroundPackage(eventList)

        if (foregroundPkg == null) {
            // Screen off or no activity — no attribution this tick
            currentForegroundPkg?.let { pkg ->
                // Browser left foreground
                if (BrowserRegistry.isKnownBrowser(pkg)) {
                    BrowserAccessibilityService.domainCache.clear(pkg)
                    browserForegroundPkg = null
                    ticksWithoutDomain = 0
                }
            }
            currentForegroundPkg = null
            return
        }

        val prevPkg = currentForegroundPkg

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

        currentForegroundPkg = foregroundPkg
        val zone = ZoneId.systemDefault()
        val day = UsageDay.currentKey(zone)

        // Record app usage
        val db = AppDatabase.getInstance(this)
        val label = foregroundPkg // Use package name as label; can be refined

        incrementCounter(db, day, "app", foregroundPkg, label)

        // Record web usage if in a known browser with a readable domain
        if (BrowserRegistry.isKnownBrowser(foregroundPkg)) {
            val domain = BrowserAccessibilityService.domainCache.get(foregroundPkg)

            if (domain != null) {
                // Domain successfully read — record web usage
                incrementCounter(db, day, "web", domain, domain)
                ticksWithoutDomain = 0
            } else {
                ticksWithoutDomain++
                // First ~30 seconds (3 ticks) grace for typing a URL
                // After that, no web attribution — still counts as app time
            }
        } else {
            ticksWithoutDomain = 0
        }
    }

    private suspend fun incrementCounter(
        db: AppDatabase,
        day: String,
        kind: String,
        subject: String,
        label: String
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
            increment = 10  // 10-second tick
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
        if (unsynced.isEmpty()) return

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

        val prefs = getSharedPreferences("pcontrol", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val deviceToken = prefs.getString("device_token", "") ?: ""
        if (serverUrl.isEmpty() || deviceToken.isEmpty()) return

        val client = SyncClient(serverUrl, deviceToken)
        val cachedPolicyVersion = prefs.getInt("policy_version", 0)

        val request = SyncRequest(
            deviceTime = java.time.Instant.now().toString(),
            policyVersion = cachedPolicyVersion,
            events = events
        )

        val response = client.sync(request)
        if (response == null) return // Network error, retry next sync

        // Mark synced counters
        for (counter in unsynced) {
            db.usageCounterDao().markSynced(counter.day, counter.kind, counter.subject)
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
            prefs.edit().putInt("policy_version", response.policy.version).apply()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "pcontrol tracker",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
