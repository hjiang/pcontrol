package com.pcontrol.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class BootReceiverTest {

    private fun nextStartedService() =
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun `boot completed starts the tracker foreground service`() {
        BootReceiver().onReceive(
            org.robolectric.RuntimeEnvironment.getApplication(),
            Intent(Intent.ACTION_BOOT_COMPLETED)
        )
        val service = nextStartedService()
        assertNotNull(service)
        assertEquals(
            "com.pcontrol.app/.TrackerService",
            service.component?.flattenToShortString()
        )
    }

    @Test
    fun `package replaced restarts the tracker foreground service`() {
        // An in-place APK update kills running services and does NOT
        // auto-restart them (including START_STICKY). Without this, every
        // update — including auto-update installs — leaves the tracker dead
        // until the next boot or manual app open.
        BootReceiver().onReceive(
            org.robolectric.RuntimeEnvironment.getApplication(),
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        val service = nextStartedService()
        assertNotNull(service)
        assertEquals(
            "com.pcontrol.app/.TrackerService",
            service.component?.flattenToShortString()
        )
    }

    @Test
    fun `unrelated broadcasts start nothing`() {
        BootReceiver().onReceive(
            org.robolectric.RuntimeEnvironment.getApplication(),
            Intent(Intent.ACTION_TIME_TICK)
        )
        assertNull(nextStartedService())
    }
}
