package com.pcontrol.app.update

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SignatureVerifierTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `matchesInstalled rejects unreadable archive via matchesPackageName`() {
        // A non-APK temp file cannot have its packageName read, so
        // matchesPackageName fails closed → reject.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy")
            deleteOnExit()
        }
        assertFalse(SignatureVerifier.matchesInstalled(context, apkFile))
    }

    @Test
    fun `matchesInstalled rejects missing file`() {
        assertFalse(SignatureVerifier.matchesInstalled(context, File("/nonexistent/missing.apk")))
    }
}
