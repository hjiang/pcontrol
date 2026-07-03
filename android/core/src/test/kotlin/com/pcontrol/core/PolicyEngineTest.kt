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

        val neverBlock = PolicyEngine.BASE_NEVER_BLOCK_PACKAGES + setOf(
            "com.android.launcher",
            "com.android.dialer",
            "com.oneplus.launcher",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
        )
        for (pkg in neverBlock) {
            val result = PolicyEngine.evaluateApp(
                pkg = pkg,
                appSeconds = counters.firstOrNull { it.subject == pkg }?.seconds ?: 999999,
                allCounters = counters,
                policy = policy,
                neverBlock = neverBlock
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

        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 120,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Stage 5: Web in restricted mode (total hit, domain excluded) ──

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
        assertEquals(600, total)
    }

    // ════════════════════════════════════════════════════════════════════
    // Stage 6: Restricted browsing mode
    // ════════════════════════════════════════════════════════════════════

    // ── Registered browser + total hit ──────────────────────────────

    @Test
    fun `total hit with registered browser returns ALLOW in evaluateApp (defers to Web)`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )
        val browser = BrowserContext(
            isRegistered = true,
            currentDomain = "khanacademy.org",
            ticksWithoutDomain = 0
        )

        // Total is hit (30 min = 1800s), but browser is registered → ALLOW
        // (restricted browsing via evaluateWeb takes over)
        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Browser",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy,
            browser = browser
        ))
    }

    @Test
    fun `total hit with unregistered browser returns BLOCK_APP`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.UnknownBrowser", "Browser", 1800, 0)
        )
        val browser = BrowserContext(
            isRegistered = false,
            currentDomain = null,
            ticksWithoutDomain = 0
        )

        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.UnknownBrowser",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy,
            browser = browser
        ))
    }

    @Test
    fun `total hit with excluded app returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("app", "com.example.Study"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Study", "Study", 1800, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.example.Study",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Registered browser + total hit + domain evaluation ──────────

    @Test
    fun `total hit registered browser excluded domain returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = "khanacademy.org",
            webSeconds = 0,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `total hit registered browser excluded subdomain returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = "www.khanacademy.org",
            webSeconds = 0,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `total hit registered browser non-excluded domain returns BLOCK_WEB`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )

        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "youtube.com",
            webSeconds = 0,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Grace period (null domain + total hit) ──────────────────────

    @Test
    fun `total hit null domain within grace returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = null,
            webSeconds = 0,
            allCounters = counters,
            policy = policy,
            ticksWithoutDomain = 0
        ))
    }

    @Test
    fun `total hit null domain at exactly grace limit returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = null,
            webSeconds = 0,
            allCounters = counters,
            policy = policy,
            ticksWithoutDomain = PolicyEngine.TICKS_WITHOUT_DOMAIN_GRACE
        ))
    }

    @Test
    fun `total hit null domain past grace limit returns BLOCK_WEB`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 1800, 0)
        )

        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = null,
            webSeconds = 0,
            allCounters = counters,
            policy = policy,
            ticksWithoutDomain = PolicyEngine.TICKS_WITHOUT_DOMAIN_GRACE + 1
        ))
    }

    @Test
    fun `total NOT hit null domain returns ALLOW`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = emptyList(),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 600, 0)
        )

        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateWeb(
            domain = null,
            webSeconds = 0,
            allCounters = counters,
            policy = policy,
            ticksWithoutDomain = 999 // even well past grace, total NOT hit → ALLOW
        ))
    }

    // ── Per-app limit takes precedence over restricted browsing ─────

    @Test
    fun `browser per-app limit exhausted still BLOCK_APP even if registered and total hit`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = listOf(LimitDef("app", "com.example.Browser", 1)),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 120, 0)
        )
        val browser = BrowserContext(
            isRegistered = true,
            currentDomain = "khanacademy.org",
            ticksWithoutDomain = 0
        )

        // Per-app limit (1 min = 60s) is exceeded (120s) → BLOCK_APP
        // Even though total NOT hit and domain is excluded
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Browser",
            appSeconds = 120,
            allCounters = counters,
            policy = policy,
            browser = browser
        ))
    }

    // ── Per-site limit in restricted mode ───────────────────────────

    @Test
    fun `excluded domain with its own per-site limit exhausted returns BLOCK_WEB`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = listOf(LimitDef("web", "khanacademy.org", 10)),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Browser", "Browser", 300, 0),
            UsageCounter("2026-07-03", "web", "khanacademy.org", "Khan", 600, 0)
        )

        // Total NOT hit (300s = 5min < 120min)
        // Per-site limit hit (600s = 10min ≥ 10min) → BLOCK_WEB
        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "khanacademy.org",
            webSeconds = 600,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── Regression: Stage 5 behaviour when total NOT hit ────────────

    @Test
    fun `stage5 regression per-site warn still works when total not hit`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = listOf(LimitDef("web", "youtube.com", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "youtube.com", "YouTube", 1620, 0)
        )

        assertEquals(Verdict.WARN, PolicyEngine.evaluateWeb(
            domain = "youtube.com",
            webSeconds = 1620,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── F2: verdict ordering — BLOCK beats WARN ────────────────────

    @Test
    fun `per-app WARN band but total limit hit returns BLOCK_APP`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 60,
            limits = listOf(LimitDef("app", "com.example.Game", 30)),
            exclusions = emptyList()
        )
        // App in per-app WARN band (27/30 = 1620/1800 = 90%)
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.example.Game", "Game", 1620, 0),
            UsageCounter("2026-07-03", "app", "com.example.Other", "Other", 2000, 0)
        )
        // Total = 1620 + 2000 = 3620 > 3600 (60 min) → total limit BLOCK
        // Per-app WARN band should not prevent total BLOCK from winning
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.example.Game",
            appSeconds = 1620,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `per-site WARN band but total limit hit and domain not excluded returns BLOCK_WEB`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 60,
            limits = listOf(LimitDef("web", "youtube.com", 30)),
            exclusions = emptyList()
        )
        // youtube.com in per-site WARN band (27/30 min = 1620/1800s = 90%)
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.android.chrome", "Chrome", 3620, 0),
            UsageCounter("2026-07-03", "web", "youtube.com", "YouTube", 1620, 0)
        )
        // Total = 3620 + 1620 = 5240 > 3600 (60 min) → total limit BLOCK
        // Per-site WARN should not prevent total limit BLOCK_WEB from winning
        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "youtube.com",
            webSeconds = 1620,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `per-site WARN band total limit hit domain excluded returns WARN`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 60,
            limits = listOf(LimitDef("web", "khanacademy.org", 30)),
            exclusions = listOf(ExclusionDef("web", "khanacademy.org"))
        )
        // khanacademy.org in per-site WARN band (27/30 min = 1620/1800s = 90%)
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.android.chrome", "Chrome", 3620, 0),
            UsageCounter("2026-07-03", "web", "khanacademy.org", "Khan", 1620, 0)
        )
        // Total = 3620 > 3600 (60 min) → total limit hit
        // But domain is excluded → restricted mode ALLOWs it
        // Per-site WARN should still fire because the domain IS in its WARN band
        assertEquals(Verdict.WARN, PolicyEngine.evaluateWeb(
            domain = "khanacademy.org",
            webSeconds = 1620,
            allCounters = counters,
            policy = policy
        ))
    }

    // ── F7: suffix matching for domains only ────────────────────────

    @Test
    fun `app limit on android dot chrome does not match com dot android dot chrome`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("app", "android.chrome", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.android.chrome", "Chrome", 1800, 0)
        )

        // "android.chrome" should NOT suffix-match "com.android.chrome"
        assertEquals(Verdict.ALLOW, PolicyEngine.evaluateApp(
            pkg = "com.android.chrome",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `app exclusion on android dot chrome does not match com dot android dot chrome`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 30,
            limits = emptyList(),
            exclusions = listOf(ExclusionDef("app", "android.chrome"))
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "app", "com.android.chrome", "Chrome", 1800, 0)
        )

        // Exclusion "android.chrome" should NOT match "com.android.chrome"
        // Total = 1800 = 30 min → BLOCK (exclusion doesn't apply)
        assertEquals(Verdict.BLOCK_APP, PolicyEngine.evaluateApp(
            pkg = "com.android.chrome",
            appSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `domain suffix matching still works for web limits`() {
        val policy = PolicyV2(
            limits = listOf(LimitDef("web", "youtube.com", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "m.youtube.com", "YouTube", 1800, 0)
        )

        // "youtube.com" should suffix-match "m.youtube.com" for web
        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "m.youtube.com",
            webSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }

    @Test
    fun `stage5 regression per-site block still works when total not hit`() {
        val policy = PolicyV2(
            totalDailyLimitMinutes = 120,
            limits = listOf(LimitDef("web", "youtube.com", 30)),
            exclusions = emptyList()
        )
        val counters = listOf(
            UsageCounter("2026-07-03", "web", "youtube.com", "YouTube", 1800, 0)
        )

        assertEquals(Verdict.BLOCK_WEB, PolicyEngine.evaluateWeb(
            domain = "youtube.com",
            webSeconds = 1800,
            allCounters = counters,
            policy = policy
        ))
    }
}
