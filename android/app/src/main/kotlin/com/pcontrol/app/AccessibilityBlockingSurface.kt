package com.pcontrol.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * A touch-consuming overlay attached by the bound accessibility service.
 *
 * The service owns both the [WindowManager] view and its teardown. This is an
 * accessibility overlay, not an application overlay, so it neither starts an
 * activity nor requires SYSTEM_ALERT_WINDOW.
 */
class AccessibilityBlockingSurface(
    private val service: AccessibilityService,
    private val onGoHome: () -> Unit
) : BlockingSurface {
    companion object {
        private const val TAG = "BlockingSurface"
    }

    private var view: View? = null
    private var windowManager: WindowManager? = null
    private var request: BlockRequest? = null

    override fun show(request: BlockRequest): PresentationOutcome {
        val existing = view
        if (existing != null) {
            if (!existing.isAttachedToWindow) {
                // The platform may detach an accessibility overlay without
                // notifying the service. Clear stale ownership and reattach.
                view = null
                windowManager = null
                this.request = null
            } else {
                val unchanged = this.request == request
                bind(existing, request)
                this.request = request
                return if (unchanged) PresentationOutcome.ALREADY_SHOWN else PresentationOutcome.SHOWN
            }
        }

        return try {
            val overlayContext = overlayContext()
            val inflated = LayoutInflater.from(blockingThemeContext(overlayContext))
                .inflate(R.layout.activity_blocked, null, false)
            bind(inflated, request)
            configureWindowInsets(inflated)
            val manager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // Establish ownership before addView so a partially attached view
            // is still removed by the catch-path cleanup.
            view = inflated
            windowManager = manager
            manager.addView(inflated, layoutParams())
            ViewCompat.requestApplyInsets(inflated)
            this.request = request
            PresentationOutcome.SHOWN
        } catch (e: Exception) {
            Log.w(TAG, "Unable to attach accessibility blocking surface for ${request.subject}", e)
            // Best effort cleanup in case addView partially succeeded.
            dismiss()
            PresentationOutcome.FAILED
        }
    }

    override fun dismiss() {
        val attached = view ?: return
        try {
            windowManager?.removeViewImmediate(attached)
        } catch (e: IllegalArgumentException) {
            // The platform already detached it; clear local ownership below.
            Log.w(TAG, "Blocking surface was already detached", e)
        } finally {
            view = null
            windowManager = null
            request = null
        }
    }

    override fun isAttached(): Boolean = view?.isAttachedToWindow == true

    private fun bind(view: View, request: BlockRequest) {
        AccessibilityBlockingContentRenderer.render(view, request, onGoHome)
    }

    /** Applies system-bar insets once to the accessibility-owned scroll content. */
    private fun configureWindowInsets(view: View) {
        val root = requireNotNull(view.findViewById<View>(R.id.blocked_root))
        val content = requireNotNull(view.findViewById<View>(R.id.blocked_content))
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        val baseLeft = content.paddingLeft
        val baseRight = content.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom,
            )
            insets
        }
    }

    private fun overlayContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return service

        // AccessibilityService is not itself a visual Context on HyperOS 3 / API
        // 36. Associate it with the default display first; calling
        // service.createWindowContext directly throws before addView.
        val display = service.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return service
        return service.createDisplayContext(display).createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touchable by default; not focusable so system navigation remains usable.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.OPAQUE
    )
}

/**
 * Binds one validated [BlockRequest] to the accessibility-owned blocking view.
 *
 * Preconditions: [view] is inflated from `activity_blocked.xml`; [request]
 * satisfies its nonblank subject/message invariant.
 * Postconditions: content and visibility match [request], allowed sites remain
 * non-clickable, and the Home control invokes [onGoHome] once per click.
 */
/**
 * Wraps a display-associated overlay context in the dedicated blocked-screen
 * palette without changing which context owns the accessibility window.
 *
 * Postcondition: resources inflated from the result resolve `Pcontrol.Blocked`
 * theme attributes while window services still delegate to [base].
 */
internal fun blockingThemeContext(base: Context): Context =
    ContextThemeWrapper(base, R.style.Pcontrol_Blocked)

internal object AccessibilityBlockingContentRenderer {
    fun render(view: View, request: BlockRequest, onGoHome: () -> Unit) {
        requireNotNull(view.findViewById<TextView>(R.id.blocked_message)).text = request.message

        val subjectCard = requireNotNull(view.findViewById<View>(R.id.blocked_subject_card))
        val subject = requireNotNull(view.findViewById<TextView>(R.id.blocked_subject))
        subject.text = request.subject
        subjectCard.visibility = if (request.subject.isBlank()) View.GONE else View.VISIBLE

        val allowed = requireNotNull(view.findViewById<TextView>(R.id.blocked_allowed_sites))
        if (request.allowedSites.isEmpty()) {
            allowed.visibility = View.GONE
        } else {
            val sites = request.allowedSites.joinToString(separator = "\n") { site ->
                view.context.getString(R.string.blocked_allowed_site_item, site)
            }
            allowed.text = view.context.getString(
                R.string.blocked_allowed_sites_format,
                view.context.getString(R.string.blocked_allowed_sites_label),
                sites,
            )
            allowed.visibility = View.VISIBLE
        }
        requireNotNull(view.findViewById<Button>(R.id.blocked_go_home))
            .setOnClickListener { onGoHome() }
    }
}
