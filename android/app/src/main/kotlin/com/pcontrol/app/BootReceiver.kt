package com.pcontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [TrackerService] after events that kill it with no auto-restart:
 * device boot, and in-place package replacement. An APK update stops all
 * running services and START_STICKY does *not* reschedule them — without
 * handling ACTION_MY_PACKAGE_REPLACED, every update (including auto-update
 * installs) leaves the tracker dead until the next boot or a manual app
 * open (plan 15).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val serviceIntent = Intent(context, TrackerService::class.java)
                context?.startForegroundService(serviceIntent)
            }
        }
    }
}
