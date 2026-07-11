package com.pcontrol.app

import android.content.Context
import android.util.Log
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.WarnedSubjectEntity
import com.pcontrol.core.BrowserContext
import com.pcontrol.core.PolicyEngine
import com.pcontrol.core.PolicyV2
import com.pcontrol.core.UsageDay
import com.pcontrol.core.Verdict
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.ZoneId

/**
 * Shared app-blocking enforcement logic used by both [TrackerService] and
 * [BrowserAccessibilityService].
 *
 * Loads the cached policy and today's usage counters, evaluates the
 * verdict via [PolicyEngine], and delegates to [Enforcer].
 *
 * The accessibility service calls this on [TYPE_WINDOW_STATE_CHANGED] events
 * so app blocking works even when the TrackerService tick loop is throttled
 * by OEM battery management (e.g. MIUI).
 *
 * @param context Android context for DB, package manager, and activity launches.
 * @param db injectable for testing; defaults to the [AppDatabase] singleton.
 */
class BlockingCoordinator(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {
    companion object {
        private const val TAG = "BlockingCoordinator"
    }

    private val labelCache = mutableMapOf<String, String>()

    /**
     * Checks if [pkg] should be blocked and launches [BlockedActivity] if so.
     *
     * Returns true if an enforcement action was taken (BLOCK_APP), false
     * otherwise (ALLOW, WARN already-warned, no policy, never-block, etc.).
     *
     * This is a suspend function — callers must invoke it from a coroutine
     * (e.g. on Dispatchers.IO).
     */
    suspend fun checkAndEnforceApp(
        pkg: String,
        startActivity: (android.content.Intent) -> Boolean = { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "startActivity failed: ${e.message}")
                false
            }
        }
    ): Boolean {
        Log.w(TAG, "checkAndEnforceApp called for pkg=$pkg")
        val neverBlockSet = NeverBlockResolver.resolve(context)
        if (pkg in neverBlockSet) {
            Log.w(TAG, "  skipped: $pkg is in neverBlockSet")
            return false
        }

        val zone = ZoneId.systemDefault()
        val day = UsageDay.currentKey(zone)

        val allCounters = db.usageCounterDao().getDay(day).map { e ->
            com.pcontrol.core.UsageCounter(
                day = e.day,
                kind = e.kind,
                subject = e.subject,
                label = e.label,
                seconds = e.seconds,
                syncedSeconds = e.syncedSeconds
            )
        }

        val policyEntity = db.cachedPolicyDao().get()
        val policy = policyEntity?.let { parsePolicy(it.json) } ?: run {
            Log.w(TAG, "  skipped: no cached policy (entity=${policyEntity != null})")
            return false
        }

        val isKnownBrowser = BrowserRegistry.isKnownBrowser(pkg)
        val browserContext = if (isKnownBrowser) {
            BrowserContext(isRegistered = true, currentDomain = null, ticksWithoutDomain = 0)
        } else null

        val appSeconds = allCounters
            .firstOrNull { it.kind == "app" && it.subject == pkg }?.seconds ?: 0
        val appVerdict = PolicyEngine.evaluateApp(
            pkg, appSeconds, allCounters, policy, browserContext, neverBlockSet
        )

        if (appVerdict == Verdict.ALLOW) {
            Log.w(TAG, "  verdict=ALLOW for $pkg (appSeconds=$appSeconds, policy has ${policy.limits.size} limits)")
            return false
        }

        Log.w(TAG, "  verdict=$appVerdict for $pkg — launching BlockedActivity")

        val label = resolveLabel(pkg)
        val limitMessage = buildLimitMessage(pkg, label, appVerdict, appSeconds, policy)
        val allowedSites = policy.exclusions
            .filter { it.kind == "web" }
            .map { it.subject }

        Enforcer.handleVerdict(
            context = context,
            verdict = appVerdict,
            subject = pkg,
            label = label,
            day = day,
            limitMessage = limitMessage,
            allowedSites = allowedSites,
            startActivity = startActivity,
            isAlreadyWarned = { d, s ->
                runBlocking { db.warnedSubjectDao().exists(d, s) > 0 }
            },
            recordWarning = { d, s ->
                runBlocking { db.warnedSubjectDao().insert(WarnedSubjectEntity(d, s)) }
            }
        )

        return appVerdict == Verdict.BLOCK_APP
    }

    // ── shared helpers (extracted from TrackerService) ────────────────

    fun parsePolicy(json: String): PolicyV2? {
        return try {
            val jsonLib = Json { ignoreUnknownKeys = true }
            val resp = jsonLib.decodeFromString<PolicyResponse>(json)
            PolicyV2(
                version = resp.version,
                totalDailyLimitMinutes = resp.totalDailyLimitMinutes,
                warnThresholdPercent = resp.warnThresholdPercent,
                limits = resp.limits.map { l ->
                    com.pcontrol.core.LimitDef(
                        kind = l.kind,
                        subject = l.subject,
                        dailyLimitMinutes = l.dailyLimitMinutes
                    )
                },
                exclusions = resp.exclusions.map { e ->
                    com.pcontrol.core.ExclusionDef(kind = e.kind, subject = e.subject)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached policy JSON", e)
            null
        }
    }

    fun buildLimitMessage(
        subject: String,
        label: String,
        verdict: Verdict,
        seconds: Int,
        policy: PolicyV2?
    ): String {
        return when (verdict) {
            Verdict.BLOCK_APP -> "$label: limit reached"
            Verdict.BLOCK_WEB -> "$label: site blocked"
            Verdict.WARN -> {
                if (policy != null) {
                    val appLimit = policy.limits.firstOrNull {
                        (it.kind == "app" || it.kind == "web") && it.subject == subject
                    }
                    if (appLimit != null) {
                        "$label: ${seconds / 60} of ${appLimit.dailyLimitMinutes} minutes used"
                    } else if (policy.totalDailyLimitMinutes != null) {
                        "$label: ${seconds / 60} of ${policy.totalDailyLimitMinutes} minutes used"
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

    fun resolveLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            val label = context.packageManager.getApplicationLabel(appInfo).toString()
            labelCache[pkg] = label
            label
        } catch (e: Exception) {
            pkg
        }
    }
}