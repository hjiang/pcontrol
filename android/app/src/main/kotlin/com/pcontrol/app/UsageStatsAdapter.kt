package com.pcontrol.app

import android.app.usage.UsageEvents
import com.pcontrol.core.AppEvent

/**
 * Adapts Android [UsageEvents.Event] to [AppEvent] for the pure-function
 * extraction logic in [com.pcontrol.core.AppUsagePoller].
 */
object UsageStatsAdapter {

    /** Converts a list of [UsageEvents.Event]s to [AppEvent]s. */
    fun toAppEvents(events: List<UsageEvents.Event>): List<AppEvent> {
        return events.mapNotNull { event ->
            val pkg = event.packageName ?: return@mapNotNull null
            AppEvent(
                packageName = pkg,
                eventType = event.eventType
            )
        }
    }
}
