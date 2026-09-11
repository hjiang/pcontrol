package com.pcontrol.core

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
}
