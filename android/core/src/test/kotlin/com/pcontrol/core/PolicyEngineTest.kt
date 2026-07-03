package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PolicyEngineTest {

    // ── Never-block list ──────────────────────────────────────────────

    @Test
    fun `never-blocked packages always ALLOW regardless of limits`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 1,
            limits = listOf(LimitDef("app", "com.launcher", 1)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.launcher", "Launcher", 120, 0)
        )

        for (pkg in PolicyEngine.NEVER_BLOCK_PACKAGES) {
            val result = PolicyEngine.evaluateApp(
                pkg = pkg,
                appSeconds = counters.firstOrNull { it.subject == pkg }?.seconds ?: 999999,
                allCounters = counters,
                policy = policy
            )
            assertEquals(Verdict.ALLOW, result, "Should never block $pkg")
        }
    }

    // ── Per-app limit ─────────────────────────────────────────────────

    @Test
    fun `app under per-app limit returns ALLOW`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 60, 0)
        )

        // 60s = 1 min, limit is 30 min = 1800s, warn at 90% = 1620s → ALLOW
        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 60,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `app at per-app warn threshold returns WARN`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 1620, 0)
        )

        // 1620s = 27 min, 90% of 30 min = 27 min → exactly at warn threshold
        assertEquals(Verdict.WARN, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 1620,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `app just under per-app limit is WARN not BLOCK`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 1799, 0)
        )

        // 1799s = 29.98 min, under 30 min → WARN (above 90%)
        assertEquals(Verdict.WARN, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 1799,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `app at per-app hard limit returns BLOCK_APP`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 1800, 0)
        )

        // 1800s = 30 min → at exact limit → BLOCK_APP
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `app over per-app limit returns BLOCK_APP`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 2000, 0)
        )

        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 2000,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Per-app limit with excluded app ──────────────────────────────

    @Test
    fun `app with both per-app limit and exclusion still enforces per-app limit`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = listOf(ExclusionDef("app", "com.example.Game"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 1800, 0)
        )

        // §6 rule 2: "This applies even if the app is in exclusions"
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── No per-app limit ──────────────────────────────────────────────

    @Test
    fun `app with no per-app limit and under total limit returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Other", "Other", 600, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Other",
            appSeconds = 600,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Per-site limit ───────────────────────────────────────────────

    @Test
    fun `domain under per-site limit returns ALLOW`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("web", "tiktok.com", 15)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "tiktok.com", "tiktok.com", 120, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = "tiktok.com",
            webSeconds = 120,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `domain at per-site warn threshold returns WARN`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("web", "tiktok.com", 15)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "tiktok.com", "tiktok.com", 810, 0)
        )

        // 810s = 13.5 min, 90% of 15 min = 13.5 min → WARN
        assertEquals(Verdict.WARN, PolicyEngine.evaluateWeb(
            domain = "tiktok.com",
            webSeconds = 810,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `domain at per-site hard limit returns BLOCK_WEB`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("web", "tiktok.com", 15)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "tiktok.com", "tiktok.com", 900, 0)
        )

        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "tiktok.com",
            webSeconds = 900,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Domain suffix matching ────────────────────────────────────────

    @Test
    fun `domain suffix match triggers limit`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("web", "youtube.com", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "m.youtube.com", "YouTube", 1800, 0)
        )

        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "m.youtube.com",
            webSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Total limit ──────────────────────────────────────────────────

    @Test
    fun `under total limit returns ALLOW for unregulated app`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 6000, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 6000,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `at total warn threshold returns WARN for unregulated app`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 6480, 0)
        )

        // 6480s = 108 min = 90% of 120 min
        assertEquals(Verdict.WARN, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 6480,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `at total hard limit returns BLOCK_APP for unregulated app`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 7200, 0)
        )

        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 7200,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Total limit with exclusions ──────────────────────────────────

    @Test
    fun `excluded app contributes zero to counted total`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 60,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("app", "com.example.Study"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Study", "Study", 7200, 0),
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 600, 0)
        )

        // countedTotal = 600s (Game), Study excluded → 600s total
        // 600s = 10 min, under 60 min → ALLOW
        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 600,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `excluded web domain is subtracted from counted total`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 60,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 7200, 0),
            UsageCounter("2026-07-03", "web", "khanacademy.org", "Khan", 3600, 0)
        )

        // countedTotal = 7200s (Browser) - 3600s (khan excluded web) = 3600s = 60 min
        // At exactly 60 min → BLOCK_APP
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Browser",
            appSeconds = 7200,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `counted total clamped to zero`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0),
            UsageCounter("2026-07-03", "web", "khanacademy.org", "Khan", 999999, 0)
        )

        // countedTotal = 1800 - 999999 = clamped to 0
        // 0 under 30 min → ALLOW
        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Browser",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── No policy configured ─────────────────────────────────────────

    @Test
    fun `null policy always returns ALLOW`() {
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 999999, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 999999,
            allCounters = counters,
            policy = null
        ))
    }

    // ── Per-app limit takes precedence over total limit ──────────────

    @Test
    fun `per-app block takes precedence over total limit ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = listOf(LimitDef("app", "com.example.Game", 1)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 120, 0)
        )

        // Per-app limit (1 min = 60s) is hit at 120s → BLOCK_APP
        // Even though total (120 min) is well over
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 120,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Web in restricted mode (total hit, domain excluded) ──────────

    @Test
    fun `restricted mode domain excluded returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0),
            UsageCounter("2026-07-03", "web", "khanacademy.org", "Khan", 0, 0)
        )

        // Total hit (1800s ≥ 1800s = 30 min). Domain is excluded → ALLOW
        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = "khanacademy.org",
            webSeconds = 0,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `restricted mode non-excluded domain returns BLOCK_WEB`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0),
            UsageCounter("2026-07-03", "web", "youtube.com", "YouTube", 100, 0)
        )

        // Total hit (1800s + 100s - 0 excluded web = 1900s ≥ 1800s).
        // youtube.com is NOT excluded → BLOCK_WEB
        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "youtube.com",
            webSeconds = 100,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── `countedTotal` helper ────────────────────────────────────────

    @Test
    fun `countedTotal sums excluded apps and subtracts excluded web`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = emptyList(),
            exclusions = listOf(
                ExclusionDef("app", "com.example.Study"),
                ExclusionDef("web", "khanacademy.org")
            )
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Study", "Study", 3600, 0),
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 1800, 0),
            UsageCounter("2026-07-03", "web", "youtube.com", "YouTube", 600, 0),
            UsageCounter("2026-07-03", "web", "khanacademy.org", "Khan", 1200, 0)
        )

        val total = PolicyEngine.countedTotal(counters, policy.exclusions)
        // §6 formula: Σ non-excluded-app-seconds − Σ excluded-web-seconds
        // Non-excluded app: Game (1800s), Study (3600s) excluded
        // Excluded web: khanacademy.org (1200s)
        // countedTotal = 1800 − 1200 = 600
        // Non-excluded web (youtube.com) is NOT added — it's inside browser app time
        assertEquals(600, total)
    }
}
