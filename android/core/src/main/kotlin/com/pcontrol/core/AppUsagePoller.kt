package com.pcontrol.core

/**
 * Pure function logic for extracting the current foreground package
 * from a list of [AppEvent]s.
 */
object AppUsagePoller {

    /**
     * Returns the foreground package after applying [events] to an empty state.
     *
     * Precondition: [events] is ordered oldest first. This convenience method
     * is for a self-contained event sequence. Call
     * [updateForegroundPackage] when processing successive event batches.
     */
    fun extractForegroundPackage(events: List<AppEvent>): String? =
        updateForegroundPackage(previousForegroundPackage = null, events = events)

    /**
     * Applies a chronologically ordered batch of activity transitions to the
     * previously known foreground package.
     *
     * Preconditions: [events] is ordered oldest first and all events belong to
     * the same Android user. Postcondition: an empty batch preserves
     * [previousForegroundPackage], because foreground events are transitions,
     * not periodic heartbeats. A background transition clears state only when
     * it belongs to the package currently being tracked.
     */
    fun updateForegroundPackage(
        previousForegroundPackage: String?,
        events: List<AppEvent>
    ): String? {
        var foregroundPackage = previousForegroundPackage

        for (event in events) {
            when (event.eventType) {
                AppEvent.ACTIVITY_RESUMED,
                AppEvent.MOVE_TO_FOREGROUND -> foregroundPackage = event.packageName
                AppEvent.ACTIVITY_PAUSED,
                AppEvent.MOVE_TO_BACKGROUND -> {
                    if (event.packageName == foregroundPackage) {
                        foregroundPackage = null
                    }
                }
            }
        }
        return foregroundPackage
    }
}
