package com.pcontrol.app

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PackageReplacedReceiverTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `starts tracker service on MY_PACKAGE_REPLACED`() {
        // Guards the root cause of issue #75: the receiver must be registered
        // in the merged manifest for ACTION_MY_PACKAGE_REPLACED, not just
        // instantiable. onReceive() below only exercises the class itself.
        assertTrue(
            "Manifest must register a receiver for ACTION_MY_PACKAGE_REPLACED",
            shadowOf(app).hasReceiverForIntent(Intent(Intent.ACTION_MY_PACKAGE_REPLACED)),
        )

        PackageReplacedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val started = shadowOf(app).nextStartedService
        assertNotNull("TrackerService should be started on package replacement", started)
        assertEquals(TrackerService::class.java.name, started.component?.className)
    }

    @Test
    fun `ignores unrelated actions`() {
        PackageReplacedReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(app).nextStartedService)
    }
}
