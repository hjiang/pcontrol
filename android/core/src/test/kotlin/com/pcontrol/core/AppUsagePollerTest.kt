package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppUsagePollerTest {

    @Test
    fun `foreground app detected from resume event`() {
        val events = listOf(
            AppEvent("com.launcher", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.launcher", AppEvent.ACTIVITY_PAUSED),
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
        )
        assertEquals("com.game", AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `last foreground app wins`() {
        val events = listOf(
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.browser", AppEvent.ACTIVITY_RESUMED),
        )
        assertEquals("com.browser", AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `latest foreground transition wins when a package is revisited`() {
        val events = listOf(
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.browser", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
        )

        assertEquals("com.game", AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `background app is not foreground`() {
        val events = listOf(
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.game", AppEvent.ACTIVITY_PAUSED),
        )
        assertNull(AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `empty event list returns null`() {
        assertNull(AppUsagePoller.extractForegroundPackage(emptyList()))
    }

    @Test
    fun `foreground app is retained when the next event batch is empty`() {
        val foreground = AppUsagePoller.updateForegroundPackage(
            previousForegroundPackage = null,
            events = listOf(AppEvent("com.game", AppEvent.ACTIVITY_RESUMED))
        )

        assertEquals(
            "com.game",
            AppUsagePoller.updateForegroundPackage(
                previousForegroundPackage = foreground,
                events = emptyList()
            )
        )
    }

    @Test
    fun `sequential resume pause resume across three apps`() {
        val events = listOf(
            AppEvent("com.app1", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.app1", AppEvent.ACTIVITY_PAUSED),
            AppEvent("com.app2", AppEvent.ACTIVITY_RESUMED),
        )
        assertEquals("com.app2", AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `pause then resume same app`() {
        val events = listOf(
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.game", AppEvent.ACTIVITY_PAUSED),
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
        )
        assertEquals("com.game", AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `background event for another app does not clear retained foreground app`() {
        assertEquals(
            "com.game",
            AppUsagePoller.updateForegroundPackage(
                previousForegroundPackage = "com.game",
                events = listOf(AppEvent("com.browser", AppEvent.ACTIVITY_PAUSED))
            )
        )
    }
}
