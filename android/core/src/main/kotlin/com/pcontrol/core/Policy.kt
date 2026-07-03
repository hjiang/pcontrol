package com.pcontrol.core

/**
 * Pure data model for time-limit policies, matching the server's §5 schema.
 */
data class PolicyV2(
    val version: Int = 0,
    val totalDailyLimitMinutes: Int? = null,
    val warnThresholdPercent: Int = 90,
    val limits: List<LimitDef> = emptyList(),
    val exclusions: List<ExclusionDef> = emptyList()
)

data class LimitDef(
    val kind: String,       // "app" or "web"
    val subject: String,
    val dailyLimitMinutes: Int
)

data class ExclusionDef(
    val kind: String,       // "app" or "web"
    val subject: String
)

/** Verdicts that [PolicyEngine] can return. */
enum class Verdict {
    /** Allow normal usage. */
    ALLOW,
    /** Post a high-priority warning notification. */
    WARN,
    /** Launch BlockedActivity when the app comes to the foreground. */
    BLOCK_APP,
    /**
     * Perform a BACK action (with two-strikes fallback to BlockedActivity).
     * Only applies to web-domain blocks in a browser.
     */
    BLOCK_WEB,
}

/**
 * Context about the current browser session, passed to [PolicyEngine]
 * for restricted browsing mode evaluation (Stage 6).
 */
data class BrowserContext(
    /** True if this package is a known browser in [BrowserRegistry]. */
    val isRegistered: Boolean,
    /**
     * The last successfully read domain from the URL bar for the current
     * foreground session. Null when the URL bar is unreadable (new tab,
     * mid-typing, fullscreen video).
     */
    val currentDomain: String?,
    /**
     * Consecutive ticks (10s each) where [currentDomain] was null
     * during this foreground session. Reset to 0 when a domain is
     * successfully read or when the browser leaves the foreground.
     */
    val ticksWithoutDomain: Int
)
