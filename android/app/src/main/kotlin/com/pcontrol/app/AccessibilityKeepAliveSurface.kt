package com.pcontrol.app

/** Owns a non-interactive accessibility overlay used to keep monitoring schedulable. */
interface AccessibilityKeepAliveSurface {
    /** Postcondition: true means the overlay is attached and owned by this surface. */
    fun attach(): Boolean

    /** Postcondition: no overlay remains owned by this surface. */
    fun detach()

    /** True only while the platform still reports the owned view as attached. */
    fun isAttached(): Boolean
}

/**
 * Idempotent lifecycle for the accessibility keep-alive surface.
 * Failed attachment is deliberately retryable.
 */
class AccessibilityKeepAliveController(
    private val surface: AccessibilityKeepAliveSurface
) {
    private var attached = false

    fun start(): Boolean {
        if (attached && surface.isAttached()) return true
        attached = false
        attached = surface.attach()
        return attached
    }

    fun stop() {
        if (!attached) return
        try {
            surface.detach()
        } finally {
            attached = false
        }
    }
}
