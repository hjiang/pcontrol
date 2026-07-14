package com.pcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityKeepAliveControllerTest {
    @Test
    fun `start attaches once and stop detaches once`() {
        val surface = FakeKeepAliveSurface()
        val controller = AccessibilityKeepAliveController(surface)

        assertTrue(controller.start())
        assertTrue(controller.start())
        assertEquals(1, surface.attachCalls)

        controller.stop()
        controller.stop()
        assertEquals(1, surface.detachCalls)
    }

    @Test
    fun `failed attachment remains retryable`() {
        val surface = FakeKeepAliveSurface(attachResult = false)
        val controller = AccessibilityKeepAliveController(surface)

        assertFalse(controller.start())
        assertFalse(controller.start())
        assertEquals(2, surface.attachCalls)
        assertEquals(0, surface.detachCalls)
    }

    @Test
    fun `platform-detached surface is reattached on next start`() {
        val surface = FakeKeepAliveSurface()
        val controller = AccessibilityKeepAliveController(surface)

        assertTrue(controller.start())
        surface.attached = false
        assertTrue(controller.start())

        assertEquals(2, surface.attachCalls)
    }

    private class FakeKeepAliveSurface(
        private val attachResult: Boolean = true
    ) : AccessibilityKeepAliveSurface {
        var attachCalls = 0
        var detachCalls = 0
        var attached = false

        override fun attach(): Boolean {
            attachCalls++
            attached = attachResult
            return attachResult
        }

        override fun detach() {
            detachCalls++
            attached = false
        }

        override fun isAttached(): Boolean = attached
    }
}
