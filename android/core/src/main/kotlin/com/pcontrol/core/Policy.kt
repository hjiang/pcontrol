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
