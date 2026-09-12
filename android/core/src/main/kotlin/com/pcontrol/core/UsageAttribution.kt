package com.pcontrol.core

/**
 * Pure decision for whether a tracker tick may attribute usage.
 *
 * Usage counts only while the device is genuinely in use: the display is on
 * AND the keyguard is not showing locked. Locked-screen time — even with the
 * display on — is attributed to nobody, for app and website counters alike.
 */
object UsageAttribution {

    fun shouldAttribute(screenInteractive: Boolean, keyguardLocked: Boolean): Boolean =
        screenInteractive && !keyguardLocked

    /**
     * Pure state computation for a tick that is NOT attributed (screen off or
     * keyguard locked).
     *
     * Returns the distinct set of browsers whose domain cache must be cleared
     * (previous and current foreground package, filtered by [isKnownBrowser])
     * and the foreground package the tracker must move to. The caller keeps all
     * side effects: clearing the caches, resetting browser state and the
     * usage-events cursor before returning, so unlocking never replays the gap.
     */
    fun skipTransition(
        previousForegroundPkg: String?,
        foregroundPkg: String?,
        isKnownBrowser: (String) -> Boolean,
    ): AttributionSkip {
        val browsersToClear = linkedSetOf<String>()
        previousForegroundPkg?.takeIf(isKnownBrowser)?.let(browsersToClear::add)
        foregroundPkg?.takeIf(isKnownBrowser)?.let(browsersToClear::add)
        return AttributionSkip(browsersToClear, foregroundPkg)
    }
}

/** State a skipped attribution tick must move to. */
data class AttributionSkip(
    val browsersToClear: Set<String>,
    val nextForegroundPkg: String?,
)
