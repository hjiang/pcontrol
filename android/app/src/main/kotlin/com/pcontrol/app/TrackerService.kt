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
import com.pcontrol.core.BrowserContext
import com.pcontrol.core.DomainParser
import com.pcontrol.core.PolicyEngine
import com.pcontrol.core.PolicyV2
import com.pcontrol.core.UsageDay
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.UsageCounterEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
            // §9: sync immediately on service start, then every 60s.
            // 0 forces the first post-tick sync check to fire right away.
            lastSyncTime = 0L

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

        // ── Enforcement (PolicyEngine + Enforcer) ───────────────────
        runEnforcement(day, foregroundPkg, currentDomain)
    }

    private suspend fun runEnforcement(day: String, pkg: String, domain: String?) {
        val db = AppDatabase.getInstance(this)
        val allCounters = db.usageCounterDao().getDay(day)
            .map { e ->
                com.pcontrol.core.UsageCounter(
                    day = e.day,
                    kind = e.kind,
                    subject = e.subject,
                    label = e.label,
                    seconds = e.seconds,
                    syncedSeconds = e.syncedSeconds
                )
            }

        // Load cached policy
        val policyEntity = db.cachedPolicyDao().get()
        val policy = policyEntity?.let { parsePolicy(it.json) }

        val isKnownBrowser = BrowserRegistry.isKnownBrowser(pkg)
        val browserContext = if (isKnownBrowser) {
            BrowserContext(
                isRegistered = true,
                currentDomain = domain,
                ticksWithoutDomain = if (domain == null) ticksWithoutDomain else 0
            )
        } else null

        val label = pkg

        // Web exclusions = sites still allowed after the daily limit (Stage 6 task 3).
        val allowedSites = policy?.exclusions
            ?.filter { it.kind == "web" }
            ?.map { it.subject }
            ?: emptyList()

        // Evaluate app verdict (with browser context for restricted mode)
        val appSeconds = allCounters
            .firstOrNull { it.kind == "app" && it.subject == pkg }?.seconds ?: 0
        val appVerdict = PolicyEngine.evaluateApp(pkg, appSeconds, allCounters, policy, browserContext)

        if (appVerdict != com.pcontrol.core.Verdict.ALLOW) {
            val limitMessage = buildLimitMessage(pkg, label, appVerdict, appSeconds, policy)
            Enforcer.handleVerdict(
                context = this,
                verdict = appVerdict,
                subject = pkg,
                label = label,
                day = day,
                limitMessage = limitMessage,
                allowedSites = allowedSites
            )
        }

        // Evaluate web verdict (restricted mode, grace period for null domain)
        val webSeconds = allCounters
            .firstOrNull { it.kind == "web" && it.subject == domain }?.seconds ?: 0
        val webVerdict = PolicyEngine.evaluateWeb(
            domain = domain,
            webSeconds = webSeconds,
            allCounters = allCounters,
            policy = policy,
            ticksWithoutDomain = ticksWithoutDomain
        )

        if (webVerdict != com.pcontrol.core.Verdict.ALLOW) {
            val limitMessage = buildLimitMessage(domain ?: pkg, label, webVerdict, webSeconds, policy)
            Enforcer.handleVerdict(
                context = this,
                verdict = webVerdict,
                subject = domain ?: pkg,
                label = label,
                day = day,
                limitMessage = limitMessage,
                allowedSites = allowedSites
            )
        }
    }

    private fun parsePolicy(json: String): PolicyV2? {
        return try {
            val jsonLib = Json { ignoreUnknownKeys = true }
            val resp = jsonLib.decodeFromString<PolicyResponse>(json)
            PolicyV2(
                version = resp.version,
                totalDailyLimitMinutes = resp.totalDailyLimitMinutes,
                warnThresholdPercent = resp.warnThresholdPercent,
                limits = resp.limits.map { l ->
                    com.pcontrol.core.LimitDef(kind = l.kind, subject = l.subject, dailyLimitMinutes = l.dailyLimitMinutes)
                },
                exclusions = resp.exclusions.map { e ->
                    com.pcontrol.core.ExclusionDef(kind = e.kind, subject = e.subject)
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildLimitMessage(
        subject: String,
        label: String,
        verdict: com.pcontrol.core.Verdict,
        seconds: Int,
        policy: PolicyV2?
    ): String {
        return when (verdict) {
            com.pcontrol.core.Verdict.BLOCK_APP -> "$label: limit reached"
            com.pcontrol.core.Verdict.BLOCK_WEB -> "$label: site blocked"
            com.pcontrol.core.Verdict.WARN -> {
                val policyVersion = policy
                if (policyVersion != null) {
                    // Try to find per-app/per-site limit
                    val appLimit = policyVersion.limits.firstOrNull {
                        (it.kind == "app" || it.kind == "web") && it.subject == subject
                    }
                    if (appLimit != null) {
                        "$label: ${seconds / 60} of ${appLimit.dailyLimitMinutes} minutes used"
                    } else if (policyVersion.totalDailyLimitMinutes != null) {
                        "$label: ${seconds / 60} of ${policyVersion.totalDailyLimitMinutes} minutes used"
                    } else {
                        "$label: $subject limit warning"
                    }
                } else {
                    "$label: limit warning"
                }
            }
            else -> "$label: $subject"
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

        val prefs = SecretPrefs.getInstance(this)
        val serverUrl = prefs.getServerUrl()
        val deviceToken = prefs.getDeviceToken()
        if (serverUrl.isEmpty() || deviceToken.isEmpty()) return

        val client = SyncClient(serverUrl, deviceToken)
        val cachedPolicyVersion = getSharedPreferences("pcontrol", MODE_PRIVATE).getInt("policy_version", 0)

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
            getSharedPreferences("pcontrol", MODE_PRIVATE).edit().putInt("policy_version", response.policy.version).apply()
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
