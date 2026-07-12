package com.pcontrol.app.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.pcontrol.app.R

/**
 * Stage 2 — renders a [SetupUiState] into the existing main-screen status
 * [TextView]s, producing **resource-backed** strings (plan task 4: "Render
 * status text from resources, not emoji strings").
 *
 * The renderer holds no Android-permission state; it receives a prebuilt state
 * and a [Context] only to resolve strings. Permission collection stays in thin
 * Android adapters in [com.pcontrol.app.MainActivity] (Section 4.1).
 *
 * Each capability line shows its title plus the textual state ("Ready" /
 * "Action needed") so color is never the only signal (Stage 3 accessibility
 * contract). Start-button label and enabled state are derived from [canStart]
 * and [firstIncompleteRequired] — the optional updater never gates monitoring.
 */
class CapabilityRenderer(private val context: Context) {

    fun render(state: SetupUiState, views: CapabilityViews) {
        renderCapability(state.capabilities.first { it.id == CapabilityId.USAGE }, views.usage)
        renderCapability(state.capabilities.first { it.id == CapabilityId.ACCESSIBILITY }, views.accessibility)
        renderCapability(state.capabilities.first { it.id == CapabilityId.NOTIFICATIONS }, views.notifications)
        renderCapability(state.capabilities.first { it.id == CapabilityId.BATTERY }, views.battery)

        // Server card (no permission check, just configured/not configured).
        val serverCap = state.capabilities.first { it.id == CapabilityId.SERVER }
        val serverText = if (serverCap.granted) {
            context.getString(R.string.cap_server_text_ready)
        } else {
            context.getString(R.string.server_state_needed)
        }
        views.server.text = "${context.getString(R.string.section_server)} — $serverText"

        renderCapability(state.capabilities.first { it.id == CapabilityId.UPDATER }, views.updater)

        // Start CTA.
        views.startBtn.isEnabled = state.canStart
        views.startBtn.text = if (state.canStart) {
            context.getString(R.string.start_monitoring)
        } else {
            val next = state.firstIncompleteRequired
            if (next == null) {
                context.getString(R.string.start_monitoring)
            } else {
                context.getString(
                    R.string.hero_next_step,
                    context.getString(next.id.titleRes())
                )
            }
        }
    }

    private fun renderCapability(cap: SetupCapability, view: TextView) {
        val title = context.getString(cap.id.titleRes())
        val state = context.getString(
            if (cap.granted) R.string.state_ready else R.string.state_action_needed
        )
        view.text = "$title — $state"
    }
}

/** Bag of main-screen status views passed into [CapabilityRenderer.render]. */
data class CapabilityViews(
    val usage: TextView,
    val accessibility: TextView,
    val notifications: TextView,
    val battery: TextView,
    val server: TextView,
    val updater: TextView,
    val startBtn: android.widget.Button,
)

/** Map a [CapabilityId] to its title string resource. */
fun CapabilityId.titleRes(): Int = when (this) {
    CapabilityId.USAGE -> R.string.cap_usage_title
    CapabilityId.ACCESSIBILITY -> R.string.cap_accessibility_title
    CapabilityId.NOTIFICATIONS -> R.string.cap_notifications_title
    CapabilityId.BATTERY -> R.string.cap_battery_title
    CapabilityId.SERVER -> R.string.section_server
    CapabilityId.UPDATER -> R.string.updater_title
}