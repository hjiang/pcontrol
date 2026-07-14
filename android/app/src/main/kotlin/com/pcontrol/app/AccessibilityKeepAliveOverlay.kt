package com.pcontrol.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** A one-pixel, non-touchable accessibility window that keeps HyperOS from idling the service UID. */
class AccessibilityKeepAliveOverlay(
    private val service: AccessibilityService
) : AccessibilityKeepAliveSurface {
    companion object {
        private const val TAG = "AccessibilityKeepAlive"

        internal fun markerLayoutParams() = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            title = "pcontrol monitoring"
        }
    }

    private var view: View? = null
    private var windowManager: WindowManager? = null

    override fun attach(): Boolean {
        if (isAttached()) return true
        if (view != null) detach()
        return try {
            val context = overlayContext()
            val marker = View(context).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0.01f
            }
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            view = marker
            windowManager = manager
            manager.addView(marker, markerLayoutParams())
            Log.i(TAG, "Accessibility keep-alive overlay attached")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to attach accessibility keep-alive overlay", e)
            detach()
            false
        }
    }

    override fun isAttached(): Boolean = view?.isAttachedToWindow == true

    override fun detach() {
        val attached = view ?: return
        try {
            windowManager?.removeViewImmediate(attached)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Keep-alive overlay was already detached", e)
        } finally {
            view = null
            windowManager = null
        }
    }

    private fun overlayContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return service
        val display = service.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return service
        return service.createDisplayContext(display).createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
    }

}
