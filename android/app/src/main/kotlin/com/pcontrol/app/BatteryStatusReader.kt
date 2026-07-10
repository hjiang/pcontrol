package com.pcontrol.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Reads current battery state from the sticky ACTION_BATTERY_CHANGED broadcast.
 * Requires no permissions. Returns null if unavailable.
 */
data class BatteryStatus(val percent: Int, val charging: Boolean)

class BatteryStatusReader(private val context: Context) {
    fun read(): BatteryStatus? {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryStatus((level * 100 / scale).coerceIn(0, 100), charging)
    }
}
