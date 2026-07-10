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
    fun `matchesInstalled returns false when archive packageInfo is unreadable`() {
        // A dummy file that is not a real APK cannot have its packageName
        // read via getPackageArchiveInfo → fails closed via matchesPackageName.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        val result = SignatureVerifier.matchesInstalled(context, apkFile)
        assertFalse("should return false when archive packageInfo is unreadable", result)
    }

    @Test
    fun `matchesInstalled fails closed on missing file`() {
        // Non-existent APK path → getPackageArchiveInfo returns null → reject.
        val result = SignatureVerifier.matchesInstalled(context, File("/nonexistent/missing.apk"))
        assertFalse("should return false (fail closed) on missing file", result)
    }
}
