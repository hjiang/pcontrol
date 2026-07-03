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
    fun `background app is not foreground`() {
        val events = listOf(
            AppEvent("com.game", AppEvent.ACTIVITY_RESUMED),
            AppEvent("com.game", AppEvent.MOVE_TO_BACKGROUND),
        )
        assertNull(AppUsagePoller.extractForegroundPackage(events))
    }

    @Test
    fun `empty event list returns null`() {
        assertNull(AppUsagePoller.extractForegroundPackage(emptyList()))
    }

    @Test
    fun `move to foreground also detected`() {
        val events = listOf(
            AppEvent("com.app1", AppEvent.MOVE_TO_FOREGROUND),
            AppEvent("com.app1", AppEvent.MOVE_TO_BACKGROUND),
            AppEvent("com.app2", AppEvent.MOVE_TO_FOREGROUND),
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
}
