package com.pcontrol.app.update

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `matchesInstalled returns false when archive has no signing info`() {
        // When getPackageArchiveInfo returns null signing info, SignatureVerifier
        // now fails closed: unreadable archive package info → rejection.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        val result = SignatureVerifier.matchesInstalled(context, apkFile)
        assertFalse("should return false when archive packageInfo is unreadable", result)
    }

    @Test
    fun `matchesInstalled returns false when APK is non-installable file`() {
        // A non-APK file with no real package info should be rejected.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        val result = SignatureVerifier.matchesInstalled(context, apkFile)
        assertFalse("should return false for non-installable archive", result)
    }

    @Test
    fun `matchesInstalled fails closed on missing file`() {
        // Non-existent APK path → getPackageArchiveInfo returns null → reject.
        val badFile = File("/nonexistent/missing.apk")
        // This should not throw — fail closed is the graceful behavior
        val result = SignatureVerifier.matchesInstalled(context, badFile)
        assertFalse("should return false (fail closed) on missing file", result)
    }
}
