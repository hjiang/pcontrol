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

        // Deliberately absent: the real UsageEvents constants are
        // MOVE_TO_FOREGROUND = 1 and MOVE_TO_BACKGROUND = 2 — mere aliases
        // of ACTIVITY_RESUMED/PAUSED. Earlier (wrong) values 6/7 here aliased
        // SYSTEM_INTERACTION and USER_INTERACTION, so touches were consumed
        // as background transitions. Only 1/2 are ever transitions.
    }
}
