package com.pcontrol.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NeverBlockResolverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `resolved set includes base packages`() {
        val resolved = NeverBlockResolver.resolve(context)
        for (pkg in listOf("com.android.systemui", "com.android.settings", "com.pcontrol.app")) {
            assertTrue("Should include base package $pkg", pkg in resolved)
        }
    }

    @Test
    fun `resolved set includes a launcher package`() {
        val resolved = NeverBlockResolver.resolve(context)
        // The Robolectric default launcher resolution may return null
        // (no default home set), in which case the fallback launcher is used
        val launchers = resolved - setOf("com.android.systemui", "com.android.settings", "com.pcontrol.app")
        assertTrue("Should include at least one launcher/dialer package", launchers.isNotEmpty())
    }

    @Test
    fun `resolved set is non-empty`() {
        val resolved = NeverBlockResolver.resolve(context)
        assertTrue("Never-block set should not be empty", resolved.isNotEmpty())
    }

    @Test
    fun `cache returns same result within TTL`() {
        val first = NeverBlockResolver.resolve(context)
        val second = NeverBlockResolver.resolve(context)
        assertTrue("Cached result should be the same instance", first === second)
    }
}
