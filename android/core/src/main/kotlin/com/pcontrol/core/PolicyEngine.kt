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
     * Base packages that must always be in the never-block set.
     * The caller (TrackerService) constructs the full set by adding the
     * runtime-resolved default launcher and dialer via [NeverBlockResolver].
     */
    val BASE_NEVER_BLOCK_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.pcontrol.app",
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
     * All rules are evaluated first; the first BLOCK in rule order wins,
     * then the first WARN, else ALLOW (§6).
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
        browser: BrowserContext? = null,
        neverBlock: Set<String> = BASE_NEVER_BLOCK_PACKAGES
    ): Verdict {
        // Rule 1: never-block list
        if (pkg in neverBlock) return Verdict.ALLOW

        if (policy == null) return Verdict.ALLOW

        // Rule 2: per-app limit (applies even if excluded) — exact match only
        var r2Verdict = Verdict.ALLOW
        val appLimit = policy.limits.firstOrNull { it.kind == "app" && matchesSubject(it.subject, pkg, "app") }
        if (appLimit != null) {
            val limitSeconds = appLimit.dailyLimitMinutes * 60
            if (appSeconds >= limitSeconds) {
                r2Verdict = Verdict.BLOCK_APP
            } else {
                val warnSeconds = (limitSeconds * policy.warnThresholdPercent / 100)
                if (appSeconds >= warnSeconds) r2Verdict = Verdict.WARN
            }
        }

        // Rule 4: total limit
        val r4Verdict = evaluateAppTotalLimit(pkg = pkg, allCounters = allCounters, policy = policy, browser = browser)

        // Combine: first BLOCK wins, then first WARN, else ALLOW
        if (r2Verdict == Verdict.BLOCK_APP) return Verdict.BLOCK_APP
        if (r4Verdict == Verdict.BLOCK_APP) return Verdict.BLOCK_APP
        if (r2Verdict == Verdict.WARN) return Verdict.WARN
        if (r4Verdict == Verdict.WARN) return Verdict.WARN
        return Verdict.ALLOW
    }

    /**
     * Evaluates the verdict for a web-domain event within a browser.
     * Called only for known browsers with a readable domain (or for
     * the grace-period evaluation when domain is null).
     *
     * All rules are evaluated first; the first BLOCK in rule order wins,
     * then the first WARN, else ALLOW (§6).
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
            // No per-site limit to evaluate (no domain).
            // Evaluate total limit only.
            val total = countedTotal(allCounters, policy.exclusions)
            val totalLimitSeconds = policy.totalDailyLimitMinutes?.times(60) ?: return Verdict.ALLOW

            if (total < totalLimitSeconds) {
                // Total NOT hit — no restriction, but check WARN band
                val totalWarnSeconds = (totalLimitSeconds * policy.warnThresholdPercent / 100)
                return if (total >= totalWarnSeconds) Verdict.WARN else Verdict.ALLOW
            }

            // Total IS hit — restricted mode with grace period
            return if (ticksWithoutDomain > TICKS_WITHOUT_DOMAIN_GRACE) {
                Verdict.BLOCK_WEB
            } else {
                Verdict.ALLOW
            }
        }

        // Domain is readable — Rule 3: per-site limit (suffix match)
        var r3Verdict = Verdict.ALLOW
        val siteLimit = policy.limits.firstOrNull { it.kind == "web" && matchesSubject(it.subject, domain, "web") }
        if (siteLimit != null) {
            val limitSeconds = siteLimit.dailyLimitMinutes * 60
            if (webSeconds >= limitSeconds) {
                r3Verdict = Verdict.BLOCK_WEB
            } else {
                val warnSeconds = (limitSeconds * policy.warnThresholdPercent / 100)
                if (webSeconds >= warnSeconds) r3Verdict = Verdict.WARN
            }
        }

        // Rule 4: total limit with restricted browsing mode
        var r4Verdict = Verdict.ALLOW
        if (policy.totalDailyLimitMinutes != null) {
            val total = countedTotal(allCounters, policy.exclusions)
            val totalLimitSeconds = policy.totalDailyLimitMinutes * 60

            if (total >= totalLimitSeconds) {
                // Restricted browsing mode: domain excluded → ALLOW, else BLOCK_WEB
                val isExcluded = policy.exclusions.any { it.kind == "web" && matchesSubject(it.subject, domain, "web") }
                if (!isExcluded) r4Verdict = Verdict.BLOCK_WEB
            } else {
                val totalWarnSeconds = (totalLimitSeconds * policy.warnThresholdPercent / 100)
                if (total >= totalWarnSeconds) r4Verdict = Verdict.WARN
            }
        }

        // Combine: first BLOCK wins, then first WARN, else ALLOW
        if (r3Verdict == Verdict.BLOCK_WEB) return Verdict.BLOCK_WEB
        if (r4Verdict == Verdict.BLOCK_WEB) return Verdict.BLOCK_WEB
        if (r3Verdict == Verdict.WARN) return Verdict.WARN
        if (r4Verdict == Verdict.WARN) return Verdict.WARN
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
            .filter { it.kind == "app" && !isExcluded(it.subject, excludedApps, "app") }
            .sumOf { it.seconds }

        val webExcludedTotal = allCounters
            .filter { it.kind == "web" && isExcluded(it.subject, excludedWeb, "web") }
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

    /**
     * Suffix matching on dot boundaries for web domains only:
     * `youtube.com` matches `m.youtube.com`.
     * For app subjects, exact equality is required to prevent
     * `android.chrome` from matching `com.android.chrome` (§7).
     */
    private fun matchesSubject(pattern: String, subject: String, kind: String): Boolean {
        if (kind == "app") return pattern == subject
        // Web: suffix match on dot boundaries
        if (pattern == subject) return true
        return subject.endsWith(".$pattern")
    }

    private fun isExcluded(subject: String, exclusionSet: Set<String>, kind: String): Boolean {
        return exclusionSet.any { matchesSubject(it, subject, kind) }
    }

    private fun isInExclusions(subject: String, exclusions: List<ExclusionDef>): Boolean {
        return exclusions.any { it.kind == "app" && matchesSubject(it.subject, subject, "app") }
    }
}
