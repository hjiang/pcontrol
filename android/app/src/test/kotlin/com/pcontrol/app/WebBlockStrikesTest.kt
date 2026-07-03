package com.pcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBlockStrikesTest {

    @Test
    fun `single strike does not trigger fallback`() {
        val strikes = WebBlockStrikes()
        strikes.recordStrike("youtube.com", "2026-07-03")
        assertFalse(strikes.shouldFallback("youtube.com", "2026-07-03"))
    }

    @Test
    fun `two strikes do not trigger fallback`() {
        val strikes = WebBlockStrikes()
        strikes.recordStrike("youtube.com", "2026-07-03")
        strikes.recordStrike("youtube.com", "2026-07-03")
        assertFalse(strikes.shouldFallback("youtube.com", "2026-07-03"))
    }

    @Test
    fun `three strikes trigger fallback`() {
        val strikes = WebBlockStrikes()
        strikes.recordStrike("youtube.com", "2026-07-03")
        strikes.recordStrike("youtube.com", "2026-07-03")
        strikes.recordStrike("youtube.com", "2026-07-03")
        assertTrue(strikes.shouldFallback("youtube.com", "2026-07-03"))
    }

    @Test
    fun `fourth strike still triggers fallback`() {
        val strikes = WebBlockStrikes()
        repeat(4) { strikes.recordStrike("youtube.com", "2026-07-03") }
        assertTrue(strikes.shouldFallback("youtube.com", "2026-07-03"))
    }

    @Test
    fun `reset clears strikes for given subject`() {
        val strikes = WebBlockStrikes()
        repeat(3) { strikes.recordStrike("youtube.com", "2026-07-03") }
        strikes.reset("youtube.com")
        assertFalse(strikes.shouldFallback("youtube.com", "2026-07-03"))
    }

    @Test
    fun `different subjects have independent counters`() {
        val strikes = WebBlockStrikes()
        repeat(3) { strikes.recordStrike("youtube.com", "2026-07-03") }
        strikes.recordStrike("tiktok.com", "2026-07-03")
        assertTrue(strikes.shouldFallback("youtube.com", "2026-07-03"))
        assertFalse(strikes.shouldFallback("tiktok.com", "2026-07-03"))
    }

    @Test
    fun `resetAll clears everything`() {
        val strikes = WebBlockStrikes()
        strikes.recordStrike("youtube.com", "2026-07-03")
        strikes.recordStrike("tiktok.com", "2026-07-03")
        strikes.resetAll()
        assertFalse(strikes.shouldFallback("youtube.com", "2026-07-03"))
        assertFalse(strikes.shouldFallback("tiktok.com", "2026-07-03"))
    }
}
