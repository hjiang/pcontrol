package com.pcontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [TrackerService] after this app's own package is updated.
 *
 * An update (adb install -r or the in-app auto-update flow) kills the
 * process and nothing else restarts the service: the system re-binds the
 * accessibility service on its own, so the app looks alive while usage
 * tracking silently stops until the app is opened or the device reboots
 * (issue #75).
 *
 * MY_PACKAGE_REPLACED is a protected broadcast delivered only to the
 * updated app, and it is exempt from the Android 12+ foreground-service
 * start restrictions - same family as the BOOT_COMPLETED path in
 * [BootReceiver].
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, TrackerService::class.java)
            context?.startForegroundService(serviceIntent)
        }
    }
}
