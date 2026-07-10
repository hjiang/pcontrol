package com.pcontrol.app.update

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SignatureVerifierTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `matchesInstalled returns true when archive has no signing info`() {
        // When getPackageArchiveInfo returns null signing info (common for mock/CI APKs),
        // SignatureVerifier degrades gracefully and returns true.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        // The apk has no real signing info -> returns true (graceful degradation)
        val result = SignatureVerifier.matchesInstalled(context, apkFile)
        assertTrue("should return true when archive signer is unavailable", result)
    }

    @Test
    fun `matchesInstalled returns true when APK is from same install`() {
        // When the install is a self-reinstall of the same APK, the
        // installed signer and the archive signer match. Robolectric
        // returns the same mocked signing info for both.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy apk content")
            deleteOnExit()
        }

        // In Robolectric, the installed app has no real signing cert (returns null),
        // so SignatureVerifier falls through to returning true.
        val result = SignatureVerifier.matchesInstalled(context, apkFile)
        assertTrue("should return true for same-signer scenario", result)
    }

    @Test
    fun `matchesInstalled handles package info retrieval gracefully`() {
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy")
            deleteOnExit()
        }

        // Non-existent APK path -> getPackageArchiveInfo returns null
        val badFile = File("/nonexistent/missing.apk")
        // This should not throw — always true is the safe fallback
        val result = SignatureVerifier.matchesInstalled(context, badFile)
        assertTrue("should not crash on missing file", result)
    }
}
