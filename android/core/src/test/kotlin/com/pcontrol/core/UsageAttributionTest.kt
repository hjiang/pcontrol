package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageAttributionTest {

    @Test
    fun `interactive and unlocked attributes usage`() {
        assertTrue(UsageAttribution.shouldAttribute(screenInteractive = true, keyguardLocked = false))
    }

    @Test
    fun `screen off does not attribute usage`() {
        assertFalse(UsageAttribution.shouldAttribute(screenInteractive = false, keyguardLocked = false))
    }

    @Test
    fun `locked keyguard with screen on does not attribute usage`() {
        assertFalse(UsageAttribution.shouldAttribute(screenInteractive = true, keyguardLocked = true))
    }

    @Test
    fun `screen off and locked does not attribute usage`() {
        assertFalse(UsageAttribution.shouldAttribute(screenInteractive = false, keyguardLocked = true))
    }

    // --- skip transition (screen off or keyguard locked) ---

    private val vivaldi = "com.vivaldi.browser"
    private val xiaomi = "com.android.browser"
    private val known = setOf(vivaldi, xiaomi)
    private val isBrowser: (String) -> Boolean = { it in known }

    @Test
    fun `both previous and current browsers are cleared`() {
        val skip = UsageAttribution.skipTransition(
            previousForegroundPkg = vivaldi,
            foregroundPkg = xiaomi,
            isKnownBrowser = isBrowser,
        )
        assertEquals(setOf(vivaldi, xiaomi), skip.browsersToClear)
        assertEquals(xiaomi, skip.nextForegroundPkg)
    }

    @Test
    fun `only previous browser is cleared`() {
        val skip = UsageAttribution.skipTransition(
            previousForegroundPkg = vivaldi,
            foregroundPkg = "com.some.other",
            isKnownBrowser = isBrowser,
        )
        assertEquals(setOf(vivaldi), skip.browsersToClear)
        assertEquals("com.some.other", skip.nextForegroundPkg)
    }

    @Test
    fun `only current browser is cleared`() {
        val skip = UsageAttribution.skipTransition(
            previousForegroundPkg = null,
            foregroundPkg = xiaomi,
            isKnownBrowser = isBrowser,
        )
        assertEquals(setOf(xiaomi), skip.browsersToClear)
        assertEquals(xiaomi, skip.nextForegroundPkg)
    }

    @Test
    fun `no browsers to clear`() {
        val skip = UsageAttribution.skipTransition(
            previousForegroundPkg = "com.some.other",
            foregroundPkg = "com.yet.another",
            isKnownBrowser = isBrowser,
        )
        assertEquals(emptySet<String>(), skip.browsersToClear)
        assertEquals("com.yet.another", skip.nextForegroundPkg)
    }

    @Test
    fun `null foreground skips to null`() {
        val skip = UsageAttribution.skipTransition(
            previousForegroundPkg = vivaldi,
            foregroundPkg = null,
            isKnownBrowser = isBrowser,
        )
        assertEquals(setOf(vivaldi), skip.browsersToClear)
        assertEquals(null, skip.nextForegroundPkg)
    }

    @Test
    fun `same browser dedupes to one`() {
        val skip = UsageAttribution.skipTransition(
            previousForegroundPkg = vivaldi,
            foregroundPkg = vivaldi,
            isKnownBrowser = isBrowser,
        )
        assertEquals(setOf(vivaldi), skip.browsersToClear)
        assertEquals(vivaldi, skip.nextForegroundPkg)
    }
}
