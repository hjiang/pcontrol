package com.pcontrol.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pcontrol.core.Verdict

/**
 * Converts a [Verdict] into an Android enforcement action per §6.
 *
 * - [Verdict.ALLOW] → no action.
 * - [Verdict.WARN] → post a high-priority notification once per (subject, day).
 * - [Verdict.BLOCK_APP] → launch [BlockedActivity].
 * - [Verdict.BLOCK_WEB] → perform a BACK action; fall back to [BlockedActivity]
 *   if the page does not close after two strikes.
 */
object Enforcer {

    private const val WARN_CHANNEL_ID = "pcontrol_warn"
    private const val WARN_NOTIFICATION_ID_BASE = 1000

    /** In-memory fallback for WARN dedupe when no persistence callback is provided. */
    private val warnedKeys = mutableSetOf<String>()

    /** Reset in-memory warned keys. Exposed for testing only. */
    @JvmStatic
    fun resetWarnedSet() {
        warnedKeys.clear()
    }

    /**
     * Handles a verdict.
     *
     * @param context Android context for notifications and intents.
     * @param verdict the verdict from [com.pcontrol.core.PolicyEngine].
     * @param subject the app package name or web domain.
     * @param label human-readable label for notifications.
     * @param day the device-local day key "YYYY-MM-DD".
     * @param limitMessage text describing which limit was hit.
     * @param performBack lambda that performs GLOBAL_ACTION_BACK; returns true
     *                    if the back action likely removed the blocked content.
     *                    Default: performs the back action.
     * @param startActivity lambda that starts an activity; returns true on success.
     *                      Default: launches the intent via [Context.startActivity].
     */
    /** Tracks consecutive BLOCK_WEB strikes for 2-strikes fallback (§6). */
    @JvmField
    val webBlockStrikes = WebBlockStrikes()

    @JvmStatic
    @JvmOverloads
    fun handleVerdict(
        context: Context,
        verdict: Verdict,
        subject: String,
        label: String,
        day: String,
        limitMessage: String,
        performBack: () -> Boolean = { performGlobalBack(context) },
        startActivity: (Intent) -> Boolean = { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        },
        /**
         * Sites still allowed after the daily limit (web exclusions from
         * CachedPolicy). When non-empty and the block is the total-limit /
         * restricted-mode kind, BlockedActivity shows them so the kid knows
         * what still works. Per Stage 6 task 3.
         */
        allowedSites: List<String> = emptyList(),
        /** Check if subject was already warned today (may block briefly for DB I/O). */
        isAlreadyWarned: ((day: String, subject: String) -> Boolean)? = null,
        /** Record that subject was warned today (may block briefly for DB I/O). */
        recordWarning: ((day: String, subject: String) -> Unit)? = null
    ) {
        when (verdict) {
            Verdict.ALLOW -> { /* no action */ }

            Verdict.WARN -> {
                if (isAlreadyWarned != null && recordWarning != null) {
                    // Persistence path (used by TrackerService)
                    if (isAlreadyWarned(day, subject)) return
                    recordWarning(day, subject)
                    postWarningNotification(context, subject, label, limitMessage)
                } else {
                    // Fallback: in-memory dedupe (used by unit tests)
                    if (warnedKeys.contains("$subject|$day")) return
                    warnedKeys.add("$subject|$day")
                    postWarningNotification(context, subject, label, limitMessage)
                }
            }

            Verdict.BLOCK_APP -> {
                launchBlockedActivity(context, limitMessage, subject, startActivity, allowedSites)
            }

            Verdict.BLOCK_WEB -> {
                webBlockStrikes.recordStrike(subject, day)
                if (webBlockStrikes.shouldFallback(subject, day)) {
                    // 2 back-presses did not navigate away (§6 2-strikes rule)
                    launchBlockedActivity(context, limitMessage, subject, startActivity, allowedSites)
                } else {
                    // Still within first 2 strikes — perform BACK
                    performBack()
                }
            }
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private fun ensureWarnChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WARN_CHANNEL_ID,
                "Limit warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warnings when approaching time limits"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun postWarningNotification(
        context: Context,
        subject: String,
        label: String,
        limitMessage: String
    ) {
        ensureWarnChannel(context)

        val notification = NotificationCompat.Builder(context, WARN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("pcontrol limit warning")
            .setContentText(limitMessage)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(WARN_NOTIFICATION_ID_BASE + subject.hashCode(), notification)
    }

    fun launchBlockedActivity(
        context: Context,
        limitMessage: String,
        subject: String,
        startActivity: (Intent) -> Boolean = { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                android.util.Log.w("Enforcer", "startActivity failed", e)
                false
            }
        },
        allowedSites: List<String>? = null
    ) {
        val intent = Intent(context, BlockedActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("message", limitMessage)
            putExtra("subject", subject)
            if (allowedSites != null) {
                putStringArrayListExtra("allowed_sites", ArrayList(allowedSites))
            }
        }
        startActivity(intent)
    }

    /**
     * Performs `performGlobalAction(GLOBAL_ACTION_BACK)` on the running
     * [BrowserAccessibilityService] (per §6, the accessibility service owns
     * the BACK action). Returns true if the action was dispatched; the caller
     * decides two-strikes fallback to [BlockedActivity] based on whether the
     * blocked content actually disappears.
     */
    private fun performGlobalBack(context: Context): Boolean {
        return try {
            val service = BrowserAccessibilityService.instance ?: return false
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            false
        }
    }
}
