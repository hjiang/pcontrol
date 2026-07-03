package com.pcontrol.core

/**
 * Pure-function policy engine that evaluates usage counters against a policy
 * and produces a [Verdict] per §6.
 *
 * Rule evaluation order (first BLOCK wins; else first WARN; else ALLOW):
 * 1. Never-block list → always ALLOW.
 * 2. Per-app limit (exclusion irrelevant per §6 rule 2).
 * 3. Per-site limit via suffix-domain matching.
 * 4. Total limit → BLOCK_APP for non-excluded non-browser apps,
 *    restricted browsing for known browsers, BLOCK_APP for unknown browsers.
 */
object PolicyEngine {

    /**
     * Package names that are NEVER blocked, regardless of any limit.
     * §6 rule 1: launcher, system-ui, dialer, settings, and pcontrol itself.
     */
    val NEVER_BLOCK_PACKAGES = setOf(
        "com.android.launcher",
        "com.android.systemui",
        "com.android.dialer",
        "com.android.settings",
        "com.pcontrol.app",
        "com.oneplus.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
    )

    /**
     * Maximum number of consecutive ticks with an unreadable URL bar
     * before a browser is blocked in restricted mode (grace period).
     * §6: 3 ticks = 30 seconds.
     */
    const val TICKS_WITHOUT_DOMAIN_GRACE = 3

    /**
     * Evaluates the verdict for an app foreground event.
     *
     * @param pkg the foreground package name
     * @param appSeconds total tracked seconds for this package today
     * @param allCounters all today's usage counters (app + web)
     * @param policy the current policy, or null if not yet synced
     * @param browser context about the current browser session; non-null
     *                only when [pkg] is a known browser. Pass null for
     *                non-browser apps.
     */
    fun evaluateApp(
        pkg: String,
        appSeconds: Int,
        allCounters: List<UsageCounter>,
        policy: PolicyV2?,
        browser: BrowserContext? = null
    ): Verdict {
        // Rule 1: never-block list
        if (pkg in NEVER_BLOCK_PACKAGES) return Verdict.ALLOW

        if (policy == null) return Verdict.ALLOW

        // Rule 2: per-app limit (applies even if excluded)
        val appLimit = policy.limits.firstOrNull { it.kind == "app" && matchesSubject(it.subject, pkg) }
        if (appLimit != null) {
            val limitSeconds = appLimit.dailyLimitMinutes * 60
            if (appSeconds >= limitSeconds) return Verdict.BLOCK_APP
            val warnSeconds = (limitSeconds * policy.warnThresholdPercent / 100)
            if (appSeconds >= warnSeconds) return Verdict.WARN
        }

        // Rule 4: total limit
        return evaluateAppTotalLimit(pkg = pkg, allCounters = allCounters, policy = policy, browser = browser)
    }

    /**
     * Evaluates the verdict for a web-domain event within a browser.
     * Called only for known browsers with a readable domain (or for
     * the grace-period evaluation when domain is null).
     *
     * @param domain the registrable domain currently in the browser URL bar,
     *               or null if the URL bar is unreadable this tick
     * @param webSeconds total tracked seconds for this domain today
     * @param allCounters all today's usage counters (app + web)
     * @param policy the current policy, or null if not yet synced
     * @param ticksWithoutDomain number of consecutive ticks since a domain
     *                           was last read in this foreground session.
     *                           Only meaningful when [domain] is null.
     */
    fun evaluateWeb(
        domain: String?,
        webSeconds: Int,
        allCounters: List<UsageCounter>,
        policy: PolicyV2?,
        ticksWithoutDomain: Int = 0
    ): Verdict {
        if (policy == null) return Verdict.ALLOW

        // Stage 6: null domain — URL unreadable this tick
        if (domain == null) {
            val total = countedTotal(allCounters, policy.exclusions)
            val totalLimitSeconds = policy.totalDailyLimitMinutes?.times(60) ?: return Verdict.ALLOW

            if (total < totalLimitSeconds) {
                // Total NOT hit — no restriction. Missing domain just means
                // no web time this tick (app time still counts).
                return Verdict.ALLOW
            }

            // Total IS hit — restricted mode with grace period
            return if (ticksWithoutDomain > TICKS_WITHOUT_DOMAIN_GRACE) {
                Verdict.BLOCK_WEB
            } else {
                Verdict.ALLOW
            }
        }

        // Domain is readable — Rule 3: per-site limit (suffix match)
        val siteLimit = policy.limits.firstOrNull { it.kind == "web" && matchesSubject(it.subject, domain) }
        if (siteLimit != null) {
            val limitSeconds = siteLimit.dailyLimitMinutes * 60
            if (webSeconds >= limitSeconds) return Verdict.BLOCK_WEB
            val warnSeconds = (limitSeconds * policy.warnThresholdPercent / 100)
            if (webSeconds >= warnSeconds) return Verdict.WARN
        }

        // Rule 4: total limit with restricted browsing mode
        val total = countedTotal(allCounters, policy.exclusions)
        if (policy.totalDailyLimitMinutes == null) return Verdict.ALLOW
        val totalLimitSeconds = policy.totalDailyLimitMinutes * 60

        if (total >= totalLimitSeconds) {
            // Restricted browsing mode: domain excluded → ALLOW, else BLOCK_WEB
            val isExcluded = policy.exclusions.any { it.kind == "web" && matchesSubject(it.subject, domain) }
            return if (isExcluded) Verdict.ALLOW else Verdict.BLOCK_WEB
        }

        val totalWarnSeconds = (totalLimitSeconds * policy.warnThresholdPercent / 100)
        if (total >= totalWarnSeconds) return Verdict.WARN

        return Verdict.ALLOW
    }

    /**
     * Computes the counted total per §6:
     *
     *   countedTotal = Σ appSeconds(p)  for every p NOT in app-exclusions
     *                − Σ webSeconds(d)  for every d in web-exclusions
     *   clamped to ≥ 0
     */
    fun countedTotal(
        allCounters: List<UsageCounter>,
        exclusions: List<ExclusionDef>
    ): Int {
        val excludedApps = exclusions.filter { it.kind == "app" }.map { it.subject }.toSet()
        val excludedWeb = exclusions.filter { it.kind == "web" }.map { it.subject }.toSet()

        val appTotal = allCounters
            .filter { it.kind == "app" && !isExcluded(it.subject, excludedApps) }
            .sumOf { it.seconds }

        val webExcludedTotal = allCounters
            .filter { it.kind == "web" && isExcluded(it.subject, excludedWeb) }
            .sumOf { it.seconds }

        return maxOf(0, appTotal - webExcludedTotal)
    }

    // ── private helpers ──────────────────────────────────────────────

    private fun evaluateAppTotalLimit(
        pkg: String,
        allCounters: List<UsageCounter>,
        policy: PolicyV2,
        browser: BrowserContext?
    ): Verdict {
        val totalLimitMinutes = policy.totalDailyLimitMinutes ?: return Verdict.ALLOW
        val total = countedTotal(allCounters, policy.exclusions)
        val totalLimitSeconds = totalLimitMinutes * 60

        if (total >= totalLimitSeconds) {
            // §6 rule 4:
            //   - Registered browser → ALLOW (restricted browsing via evaluateWeb)
            //   - Excluded app → ALLOW (not counted in total)
            //   - Everything else → BLOCK_APP
            if (browser?.isRegistered == true) return Verdict.ALLOW
            if (isInExclusions(pkg, policy.exclusions)) return Verdict.ALLOW
            return Verdict.BLOCK_APP
        }

        val totalWarnSeconds = (totalLimitSeconds * policy.warnThresholdPercent / 100)
        if (total >= totalWarnSeconds) return Verdict.WARN

        return Verdict.ALLOW
    }

    /** Suffix matching on dot boundaries: `youtube.com` matches `m.youtube.com`. */
    private fun matchesSubject(pattern: String, subject: String): Boolean {
        if (pattern == subject) return true
        return subject.endsWith(".$pattern")
    }

    private fun isExcluded(subject: String, exclusionSet: Set<String>): Boolean {
        return exclusionSet.any { matchesSubject(it, subject) }
    }

    private fun isInExclusions(subject: String, exclusions: List<ExclusionDef>): Boolean {
        return exclusions.any { it.kind == "app" && matchesSubject(it.subject, subject) }
    }
}
