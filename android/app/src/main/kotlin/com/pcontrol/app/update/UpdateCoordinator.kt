package com.pcontrol.app.update

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Orchestrates the full auto-update pipeline:
 *
 *   fetch → compare → download → verify → install
 *
 * All dependencies are injected as interfaces so the coordinator is pure
 * orchestration over testable seams.
 *
 * The coordinator is designed to be called from the [TrackerService] tick
 * loop (the "once per ~24h" gate) or from a manual "Check for updates" button.
 */
class UpdateCoordinator(
    private val context: Context,
    private val updateState: UpdateState = UpdateState(context),
    private val versionName: String,
    private val client: UpdateClient = GitHubReleaseClient(),
    private val downloader: Downloader = ApkDownloader(context.cacheDir),
    private val verifier: Verifier = SignatureVerifier,
    private val installer: Installer = ApkInstaller
) {
    companion object {
        private const val TAG = "UpdateCoordinator"
    }

    /**
     * Runs the update pipeline once.
     *
     * - If [UpdateState.autoUpdateEnabled] is false, returns immediately.
     * - Checks [UpdateState.lastUpdateCheckMs] gate (≥ 24h or force=true).
     * - Fetches the latest release from GitHub.
     * - Compares versions; if up-to-date, updates timestamp and returns.
     * - Downloads the APK.
     * - Verifies the APK signature against the installed app.
     * - Installs the APK (prompts system install dialog).
     * - Updates [UpdateState] bookkeeping on success.
     *
     * @param force If true, ignores the 24h gate and checks immediately.
     * @return The [UpdateResult] describing what happened.
     */
    fun runOnce(force: Boolean = false): UpdateResult {
        if (!updateState.autoUpdateEnabled) {
            return UpdateResult.DISABLED
        }

        val now = System.currentTimeMillis()
        if (!force && (now - updateState.lastUpdateCheckMs < UpdateState.UPDATE_CHECK_INTERVAL_MS)) {
            return UpdateResult.SKIPPED
        }

        // Always refresh the check timestamp so we don't hammer on failures
        updateState.lastUpdateCheckMs = now

        // 1. Fetch latest release info
        val updateInfo = client.fetchLatestRelease() ?: return UpdateResult.NETWORK_ERROR

        // 2. Compare versions
        val installedVersion = versionName
        val result = com.pcontrol.core.Version.needsUpdate(installedVersion, updateInfo.version)
        if (result != com.pcontrol.core.UpdateCheckResult.NEEDS_UPDATE) {
            return UpdateResult.UP_TO_DATE
        }

        Log.i(TAG, "Update v${updateInfo.version} available (installed: v$installedVersion)")

        // 3. Download the APK
        val apkFile = downloader.download(updateInfo.downloadUrl, updateInfo.version)
            ?: return UpdateResult.DOWNLOAD_FAILED

        // 4. Verify signature
        if (!verifier.matchesInstalled(context, apkFile)) {
            Log.w(TAG, "Signature mismatch — refusing to install v${updateInfo.version}")
            apkFile.delete()
            updateState.downloadedApkPath = ""
            return UpdateResult.SIGNATURE_MISMATCH
        }

        // 5. Install
        if (!installer.install(context, apkFile)) {
            // FileProvider or activity launch failure
            updateState.downloadedApkPath = apkFile.absolutePath
            return UpdateResult.INSTALL_FAILED
        }

        updateState.downloadedApkPath = apkFile.absolutePath
        Log.i(TAG, "Install dialog presented for v${updateInfo.version}")
        return UpdateResult.INSTALL_TRIGGERED
    }
}

/**
 * Result of an update check cycle.
 */
sealed class UpdateResult {
    /** Auto-update is disabled via prefs. */
    data object DISABLED : UpdateResult()
    /** Check interval hasn't elapsed yet (only when force=false). */
    data object SKIPPED : UpdateResult()
    /** Network error or no APK asset on the release. */
    data object NETWORK_ERROR : UpdateResult()
    /** Installed version is current. */
    data object UP_TO_DATE : UpdateResult()
    /** Download failed (network error during download). */
    data object DOWNLOAD_FAILED : UpdateResult()
    /** Downloaded APK signature doesn't match installed app. */
    data object SIGNATURE_MISMATCH : UpdateResult()
    /** System install dialog could not be launched. */
    data object INSTALL_FAILED : UpdateResult()
    /** System install dialog was presented successfully. */
    data object INSTALL_TRIGGERED : UpdateResult()
}

// ── Injected interfaces ───────────────────────────────────────────

/** Fetches update info from a remote source. */
interface UpdateClient {
    fun fetchLatestRelease(): UpdateInfo?
}

/** Downloads an APK from a URL to local storage. */
interface Downloader {
    fun download(url: String, version: String): File?
}

/** Verifies that a downloaded APK is signed correctly. */
interface Verifier {
    fun matchesInstalled(context: Context, apkFile: File): Boolean
}

/** Triggers the system package installer. */
interface Installer {
    fun install(context: Context, apkFile: File): Boolean
}
