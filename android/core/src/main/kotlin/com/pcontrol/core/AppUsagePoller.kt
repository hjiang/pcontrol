package com.pcontrol.core

/**
 * Pure function logic for extracting the current foreground package
 * from a list of [AppEvent]s.
 */
object AppUsagePoller {

    /**
     * Given a list of [AppEvent]s (in time order, oldest first),
     * returns the package name of the app currently in the foreground.
     *
     * The algorithm: scan events in order. The latest
     * [AppEvent.ACTIVITY_RESUMED] or [AppEvent.MOVE_TO_FOREGROUND] event wins.
     * If the last event for a package is [AppEvent.MOVE_TO_BACKGROUND]
     * or [AppEvent.ACTIVITY_PAUSED], that package is no longer in the foreground.
     *
     * Returns null when the screen is off or no foreground app is detected.
     */
    fun extractForegroundPackage(events: List<AppEvent>): String? {
        val packageStates = mutableMapOf<String, Boolean>() // true = foreground

        for (event in events) {
            when (event.eventType) {
                AppEvent.ACTIVITY_RESUMED,
                AppEvent.MOVE_TO_FOREGROUND -> {
                    packageStates[event.packageName] = true
                }
                AppEvent.ACTIVITY_PAUSED,
                AppEvent.MOVE_TO_BACKGROUND -> {
                    packageStates[event.packageName] = false
                }
            }
        }

        // Find the last package that ended in foreground state
        var foregroundPkg: String? = null
        for ((pkg, isForeground) in packageStates) {
            if (isForeground) {
                foregroundPkg = pkg
            }
        }
        return foregroundPkg
    }
}
