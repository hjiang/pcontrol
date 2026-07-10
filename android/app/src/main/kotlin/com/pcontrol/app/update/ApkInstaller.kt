package com.pcontrol.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Triggers the Android system package installer for a downloaded APK.
 *
 * Uses [FileProvider] `content://` URI and `Intent.ACTION_VIEW` with
 * `application/vnd.android.package-archive` MIME type (the supported path
 * for Android 8+ / targetSdk 37). The deprecated `ACTION_INSTALL_PACKAGE`
 * is intentionally not used.
 *
 * This class is intentionally thin — the system dialog is unavoidable for
 * a non-device-owner app. Unit testing the dialog itself is infeasible;
 * acceptance is manual (see AGENTS.md).
 */
object ApkInstaller : Installer {

    /**
     * Prompts the system install dialog for the given APK file.
     *
     * @param context Any Android context (activity or application).
     * @param apkFile The downloaded APK file (must be in a location exposed
     *                by FileProvider, i.e. under `cacheDir/updates/`).
     * @return `true` if the intent was launched successfully.
     */
    override fun install(context: Context, apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
