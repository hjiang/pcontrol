package com.pcontrol.core

/**
 * Pure decision for whether a tracker tick may attribute usage.
 *
 * Usage counts only while the device is genuinely in use: the display is on
 * AND the keyguard is not showing locked. Locked-screen time — even with the
 * display on — is attributed to nobody, for app and website counters alike.
 */
object UsageAttribution {

    fun shouldAttribute(screenInteractive: Boolean, keyguardLocked: Boolean): Boolean =
        screenInteractive && !keyguardLocked
}
