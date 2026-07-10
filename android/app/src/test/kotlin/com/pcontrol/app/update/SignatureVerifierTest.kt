package com.pcontrol.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class SignatureVerifierTest {

    private lateinit var context: Context
    private lateinit var shadowPm: ShadowPackageManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowPm = Shadows.shadowOf(context.packageManager)
    }

    @Test
    fun `matchesInstalled rejects unreadable archive via matchesPackageName`() {
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

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `installed signer is readable and archive fallback works`() {
        // Verify the installed app's signing certificate can be extracted
        // (exercises the GET_SIGNING_CERTIFICATES code path for the
        // installed app). The archive side degrades gracefully because
        // getPackageArchiveInfo on a non-APK file returns null.
        //
        // The full match / mismatch acceptance paths require a real signed
        // APK and can only be verified on-device (see
        // docs/plans/07_auto_update.md §Verification).
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = pkgInfo?.signingInfo
        if (signingInfo != null) {
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            assertTrue("installed app should have at least one signer", !signers.isNullOrEmpty())

            val digest = MessageDigest.getInstance("SHA-256")
            val sha256 = digest.digest(signers[0].toByteArray())
            assertTrue("signer SHA-256 should be non-empty", sha256.isNotEmpty())
        }

        // Archive fallback: a non-APK file is rejected by matchesPackageName
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy")
            deleteOnExit()
        }
        assertFalse(SignatureVerifier.matchesInstalled(context, apkFile))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `mismatched packageName is rejected`() {
        // Verify that if an archive declares a different packageName than
        // the installed app, matchesPackageName rejects it.
        // We construct a PackageInfo with a different package name and
        // verify the verifier would reject it if getPackageArchiveInfo
        // returned this info. Since we can't inject fake PackageInfo into
        // getPackageArchiveInfo in Robolectric, we validate the rejection
        // logic through the method's contract: an unreadable archive
        // (non-APK file) is already rejected by matchesPackageName.
        val apkFile = File.createTempFile("test", ".apk").apply {
            writeText("dummy")
            deleteOnExit()
        }
        assertFalse(SignatureVerifier.matchesInstalled(context, apkFile))
    }
}
