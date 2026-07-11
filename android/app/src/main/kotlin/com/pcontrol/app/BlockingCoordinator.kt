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
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** The complete, non-presentation result for one foreground observation. */
data class ForegroundEvaluation(
    val appVerdict: Verdict,
    val webVerdict: Verdict,
    val appRequest: BlockRequest?,
    val webRequest: BlockRequest?,
    /** A validated controller must dispatch this before the two-strike fallback. */
    val webBack: Boolean
)

/**
 * Loads cached policy/counters and returns enforcement requests without
 * presenting them. A BLOCK_APP verdict is therefore never reported as a
 * successful block until [BlockingController] reports a presentation outcome.
 */
class BlockingCoordinator(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {
    companion object {
        private const val TAG = "BlockingCoordinator"
        private val jsonLib = Json { ignoreUnknownKeys = true }
    }

    private val labelCache = ConcurrentHashMap<String, String>()

    suspend fun evaluateForeground(
        pkg: String,
        domain: String?,
        ticksWithoutDomain: Int = 0
    ): ForegroundEvaluation {
        require(pkg.isNotBlank()) { "Foreground package must not be blank" }
        val day = UsageDay.currentKey(ZoneId.systemDefault())
        val neverBlock = NeverBlockResolver.resolve(context)
        if (pkg in neverBlock) {
            return ForegroundEvaluation(Verdict.ALLOW, Verdict.ALLOW, null, null, false)
        }

        val policyEntity = db.cachedPolicyDao().get()
        val policy = policyEntity?.let { parsePolicy(it.json) }
        if (policy == null) {
            return ForegroundEvaluation(Verdict.ALLOW, Verdict.ALLOW, null, null, false)
        }
        val counters = db.usageCounterDao().getDay(day).map { entry ->
            com.pcontrol.core.UsageCounter(
                day = entry.day,
                kind = entry.kind,
                subject = entry.subject,
                label = entry.label,
                seconds = entry.seconds,
                syncedSeconds = entry.syncedSeconds
            )
        }
        val knownBrowser = BrowserRegistry.isKnownBrowser(pkg)
        val browserContext = if (knownBrowser) {
            BrowserContext(true, domain, ticksWithoutDomain)
        } else null
        val appSeconds = counters.firstOrNull { it.kind == "app" && it.subject == pkg }?.seconds ?: 0
        val appVerdict = PolicyEngine.evaluateApp(
            pkg, appSeconds, counters, policy, browserContext, neverBlock
        )
        val label = resolveLabel(pkg)
        val allowedSites = policy.exclusions.filter { it.kind == "web" }.map { it.subject }
        val appAction = handleVerdict(
            appVerdict, pkg, label, day,
            buildLimitMessage(pkg, label, appVerdict, appSeconds, policy), allowedSites
        )

        val webSeconds = counters.firstOrNull { it.kind == "web" && it.subject == domain }?.seconds ?: 0
        val webVerdict = if (knownBrowser) {
            PolicyEngine.evaluateWeb(domain, webSeconds, counters, policy, ticksWithoutDomain)
        } else {
            Verdict.ALLOW
        }
        val webSubject = domain ?: pkg
        // An app-level block wins the composite decision, so do not dispatch
        // a web BACK action behind an app overlay.
        val webAction = if (appAction is EnforcementAction.Show) {
            EnforcementAction.None
        } else {
            handleVerdict(
                webVerdict, webSubject, label, day,
                buildLimitMessage(webSubject, label, webVerdict, webSeconds, policy), allowedSites
            )
        }
        return ForegroundEvaluation(
            appVerdict = appVerdict,
            webVerdict = webVerdict,
            appRequest = (appAction as? EnforcementAction.Show)?.request,
            webRequest = (webAction as? EnforcementAction.Show)?.request,
            webBack = webAction == EnforcementAction.Back
        )
    }

    private suspend fun handleVerdict(
        verdict: Verdict,
        subject: String,
        label: String,
        day: String,
        message: String,
        allowedSites: List<String>
    ): EnforcementAction {
        if (verdict == Verdict.WARN && db.warnedSubjectDao().exists(day, subject) > 0) {
            return EnforcementAction.None
        }
        return Enforcer.handleVerdict(
            context = context,
            verdict = verdict,
            subject = subject,
            label = label,
            day = day,
            limitMessage = message,
            allowedSites = allowedSites,
            isAlreadyWarned = { _, _ -> false },
            recordWarning = { d, s ->
                kotlinx.coroutines.runBlocking { db.warnedSubjectDao().insert(WarnedSubjectEntity(d, s)) }
            }
        )
    }

    fun parsePolicy(json: String): PolicyV2? = try {
        val response = jsonLib.decodeFromString<PolicyResponse>(json)
        PolicyV2(
            version = response.version,
            totalDailyLimitMinutes = response.totalDailyLimitMinutes,
            warnThresholdPercent = response.warnThresholdPercent,
            limits = response.limits.map {
                com.pcontrol.core.LimitDef(it.kind, it.subject, it.dailyLimitMinutes)
            },
            exclusions = response.exclusions.map { com.pcontrol.core.ExclusionDef(it.kind, it.subject) }
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse cached policy JSON", e)
        null
    }

    fun buildLimitMessage(
        subject: String,
        label: String,
        verdict: Verdict,
        seconds: Int,
        policy: PolicyV2?
    ): String = when (verdict) {
        Verdict.BLOCK_APP -> "$label: limit reached"
        Verdict.BLOCK_WEB -> "$label: site blocked"
        Verdict.WARN -> {
            val limit = policy?.limits?.firstOrNull {
                (it.kind == "app" || it.kind == "web") && it.subject == subject
            }
            when {
                limit != null -> "$label: ${seconds / 60} of ${limit.dailyLimitMinutes} minutes used"
                policy?.totalDailyLimitMinutes != null -> "$label: ${seconds / 60} of ${policy.totalDailyLimitMinutes} minutes used"
                else -> "$label: limit warning"
            }
        }
        Verdict.ALLOW -> "$label: $subject"
    }

    fun resolveLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkg, 0)
            ).toString().also { labelCache[pkg] = it }
        } catch (_: Exception) {
            pkg
        }
    }
}
