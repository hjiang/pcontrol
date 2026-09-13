package com.pcontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
        // Guard up front: Intent(Context, Class) dereferences the context
        // (ComponentName → getPackageName), so a null context must never
        // reach the constructor — the later safe-call would be too late.
        val appContext = context ?: return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val serviceIntent = Intent(appContext, TrackerService::class.java)
                try {
                    appContext.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException (API 31+) or
                    // OEM background-start IllegalStateExceptions can throw
                    // here. A crashing receiver would take down the whole
                    // process — including the bound accessibility service —
                    // exactly when we are trying to resurrect the tracker.
                    // Log it; the next boot/update/app-open retries.
                    Log.w("BootReceiver", "Failed to restart TrackerService", e)
                }
            }
        }
    }
}
