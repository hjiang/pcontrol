package com.pcontrol.core

/**
 * A minimal representation of a foreground-activity event, used by
 * [AppUsagePoller] to decouple from the Android `UsageEvents.Event` type.
 */
data class AppEvent(
    val packageName: String,
    val eventType: Int
) {
    companion object {
        const val ACTIVITY_RESUMED = 1  // matches UsageEvents.Event.ACTIVITY_RESUMED
        const val ACTIVITY_PAUSED = 2   // matches UsageEvents.Event.ACTIVITY_PAUSED
        const val MOVE_TO_FOREGROUND = 6
        const val MOVE_TO_BACKGROUND = 7
    }
}
