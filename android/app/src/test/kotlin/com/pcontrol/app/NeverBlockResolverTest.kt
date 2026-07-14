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
    fun `resolved set retains fallback launchers including Xiaomi Home`() {
        val resolved = NeverBlockResolver.resolve(context)
        assertTrue("Should retain Xiaomi Home when default resolution is absent or ambiguous", "com.miui.home" in resolved)
        assertTrue("Should retain an Android launcher fallback", "com.android.launcher" in resolved)
    }

    @Test
    fun `home candidate lookup is safe when no default home is configured`() {
        // Robolectric normally has no preferred HOME handler; discovery must be
        // non-throwing so fallback launchers still protect Home.
        assertNotNull(NeverBlockResolver.resolveHomePackages(context))
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
