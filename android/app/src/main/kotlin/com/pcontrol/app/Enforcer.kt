package com.pcontrol.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pcontrol.core.Verdict

/** The non-visual action produced by a policy verdict. */
sealed interface EnforcementAction {
    data object None : EnforcementAction
    data object Back : EnforcementAction
    data class Show(val request: BlockRequest) : EnforcementAction
}

/**
 * Converts policy verdicts into side effects that do not start activities.
 * BLOCK_APP and the BLOCK_WEB two-strike fallback return a request for the
 * accessibility-owned surface. Presentation is performed by BlockingController.
 */
object Enforcer {
    private const val WARN_CHANNEL_ID = "pcontrol_warn"
    private const val BLOCK_CHANNEL_ID = "pcontrol_block"
    private const val WARN_NOTIFICATION_ID_BASE = 1000
    private const val BLOCK_NOTIFICATION_ID = 2000

    private val warnedKeys = mutableSetOf<String>()

    @JvmStatic
    fun resetWarnedSet() { warnedKeys.clear() }

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
        allowedSites: List<String> = emptyList(),
        isAlreadyWarned: ((day: String, subject: String) -> Boolean)? = null,
        recordWarning: ((day: String, subject: String) -> Unit)? = null
    ): EnforcementAction {
        require(subject.isNotBlank()) { "A verdict subject must not be blank" }
        return when (verdict) {
            Verdict.ALLOW -> EnforcementAction.None
            Verdict.WARN -> {
                if (isAlreadyWarned != null && recordWarning != null) {
                    if (isAlreadyWarned(day, subject)) return EnforcementAction.None
                    recordWarning(day, subject)
                } else if (!warnedKeys.add("$subject|$day")) {
                    return EnforcementAction.None
                }
                postWarningNotification(context, subject, label, limitMessage)
                EnforcementAction.None
            }
            Verdict.BLOCK_APP -> EnforcementAction.Show(
                BlockRequest(BlockKind.APP, subject, limitMessage, allowedSites)
            )
            Verdict.BLOCK_WEB -> {
                if (webBlockStrikes.recordAndShouldFallback(subject, day)) {
                    EnforcementAction.Show(
                        BlockRequest(BlockKind.WEB, subject, limitMessage, allowedSites)
                    )
                } else {
                    // The controller dispatches BACK only after it verifies the
                    // captured foreground generation is still current.
                    EnforcementAction.Back
                }
            }
        }
    }

    private fun ensureChannel(context: Context, id: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun postWarningNotification(
        context: Context,
        subject: String,
        label: String,
        limitMessage: String
    ) {
        ensureChannel(context, WARN_CHANNEL_ID, "Limit warnings")
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

    /** Persistent/high-priority degraded-path notification. */
    fun postBlockFailureNotification(context: Context, message: String) {
        ensureChannel(context, BLOCK_CHANNEL_ID, "Blocked app")
        val notification = NotificationCompat.Builder(context, BLOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("pcontrol blocked app")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Keep degraded-path failures dismissible once the user has seen them.
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BLOCK_NOTIFICATION_ID, notification)
    }

}
