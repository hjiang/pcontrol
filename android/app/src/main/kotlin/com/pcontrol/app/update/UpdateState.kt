package com.pcontrol.app.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages auto-update preferences in the `"pcontrol"` SharedPreferences file.
 *
 * Keys:
 * - `auto_update_enabled` (boolean, default true) — master toggle for auto-update.
 * - `last_update_check_ms` (long, default 0) — epoch millis of the last update check.
 *   Used by the [UpdateCoordinator] and [TrackerService] gate to enforce the ~24h
 *   interval without needing a separate timer/alarm.
 */
class UpdateState(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pcontrol", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
        private const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
        private const val KEY_DOWNLOADED_APK_PATH = "downloaded_apk_path"

        /** Default interval between update checks: 24 hours. */
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, value).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, value).apply()

    /**
     * Path to the last successfully downloaded APK, or empty string.
     * Persisted so [ApkInstaller] can present a "tap to install" flow
     * even after a process restart.
     */
    var downloadedApkPath: String
        get() = prefs.getString(KEY_DOWNLOADED_APK_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOWNLOADED_APK_PATH, value).apply()
}
