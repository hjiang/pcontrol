package com.pcontrol.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Verifies that a downloaded APK is signed with the same certificate as the
 * currently installed app.
 *
 * This is a **pre-install safety check**: if the signer differs, the system
 * install dialog would ultimately reject it anyway, but we fail closed
 * earlier and present a "manual update available" message instead of a
 * doomed install prompt.
 *
 * API level handling:
 * - API 28+ : Use [PackageManager.GET_SIGNING_CERTIFICATES] with [SigningInfo].
 * - API 26-27: Use the deprecated [PackageManager.GET_SIGNATURES] path.
 * - If signing info cannot be extracted (some OEMs), degrade gracefully:
 *   return `true` and log a warning so the installer is the final judge.
 */
object SignatureVerifier : Verifier {

    private const val TAG = "SignatureVerifier"

    /**
     * Returns `true` if the downloaded APK's signing certificate SHA-256
     * matches the currently installed app's signing certificate.
     */
    override fun matchesInstalled(context: Context, apkFile: File): Boolean {
        val pm = context.packageManager
        val packageName = context.packageName

        // 0. Reject if the archive declares a different packageName than the installed app.
        //    This catches accidental mis-packaging or a manifest-squatting attack.
        if (!matchesPackageName(pm, apkFile, packageName)) return false

        // 1. Installed signer
        val installedSigner = getSignerSha256(pm, packageName, isArchive = false)
        if (installedSigner == null) {
            Log.w(TAG, "Cannot read installed app signing cert — skipping pre-check")
            return true
        }

        // 2. Downloaded archive signer
        val archiveSigner = getSignerSha256(pm, apkFile.absolutePath, isArchive = true)
        if (archiveSigner == null) {
            Log.w(TAG, "Cannot read archive signing cert — trust the installer")
            return true
        }

        return installedSigner.contentEquals(archiveSigner)
    }

    /**
     * Returns false if the archive's [PackageInfo.packageName] can be read
     * and differs from [expectedPackageName], OR if the archive's package
     * info cannot be read at all (corrupt / non-APK file). Fails closed to
     * prevent installing a package with an unexpected identity.
     */
    private fun matchesPackageName(
        pm: PackageManager,
        apkFile: File,
        expectedPackageName: String
    ): Boolean {
        val archiveInfo = try {
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read archive packageInfo — rejecting", e)
            return false
        }
        if (archiveInfo == null) {
            Log.w(TAG, "getPackageArchiveInfo returned null — rejecting")
            return false
        }
        if (archiveInfo.packageName != expectedPackageName) {
            Log.w(TAG, "Archive packageName '${archiveInfo.packageName}' != installed '$expectedPackageName' — rejecting")
            return false
        }
        return true
    }

    /**
     * Extracts the SHA-256 digest of the first signer's encoded certificate.
     *
     * @param pm The [PackageManager].
     * @param packageNameOrPath Package name for installed app, or absolute path for an APK archive.
     * @param isArchive Whether [packageNameOrPath] is a file path (true) or package name (false).
     */
    private fun getSignerSha256(
        pm: PackageManager,
        packageNameOrPath: String,
        isArchive: Boolean
    ): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256")

        // API 28+ path
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val packageInfo = if (isArchive) {
                try {
                    pm.getPackageArchiveInfo(packageNameOrPath, flags)?.also { info ->
                        // Some API levels/OEMs require sourceDir to match the archive
                        // path for signing info to be populated correctly.
                        info.applicationInfo?.sourceDir = packageNameOrPath
                        info.applicationInfo?.publicSourceDir = packageNameOrPath
                    }
                } catch (e: Exception) {
                    null
                }
            } else {
                try {
                    pm.getPackageInfo(packageNameOrPath, flags)
                } catch (e: Exception) {
                    null
                }
            }

            val signingInfo = packageInfo?.signingInfo ?: return null
            @Suppress("DEPRECATION")
            val signers = if (signingInfo.hasMultipleSigners()) {
                // Multiple signers means rotating or additional keys; hashing only
                // signers[0] would give false confidence. Defer to the installer.
                return null
            } else {
                // signingCertificateHistory may be null on some OEM builds
                signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
            }

            if (signers.isNullOrEmpty()) return null
            return digest.digest(signers[0].toByteArray())
        }

        // API 26-27 fallback: use the deprecated GET_SIGNATURES
        return getSignerSha256Legacy(pm, packageNameOrPath, isArchive, digest)
    }

    /**
     * Fallback for API 26-27 using deprecated [PackageManager.GET_SIGNATURES].
     */
    @Suppress("DEPRECATION")
    private fun getSignerSha256Legacy(
        pm: PackageManager,
        packageNameOrPath: String,
        isArchive: Boolean,
        digest: MessageDigest
    ): ByteArray? {
        val flags = PackageManager.GET_SIGNATURES
        val packageInfo = if (isArchive) {
            try {
                pm.getPackageArchiveInfo(packageNameOrPath, flags)?.also { info ->
                    info.applicationInfo?.sourceDir = packageNameOrPath
                    info.applicationInfo?.publicSourceDir = packageNameOrPath
                }
            } catch (e: Exception) {
                null
            }
        } else {
            try {
                pm.getPackageInfo(packageNameOrPath, flags)
            } catch (e: Exception) {
                null
            }
        }

        val signatures = packageInfo?.signatures ?: return null
        if (signatures.isEmpty()) return null
        return digest.digest(signatures[0].toByteArray())
    }
}
