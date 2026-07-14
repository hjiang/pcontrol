package com.pcontrol.app

/** The policy surface that is being blocked. */
enum class BlockKind { APP, WEB }

data class BlockRequest(
    val kind: BlockKind,
    val subject: String,
    val message: String,
    val allowedSites: List<String> = emptyList()
) {
    init {
        require(subject.isNotBlank()) { "A block request must have a subject" }
        require(message.isNotBlank()) { "A block request must have a message" }
    }
}

enum class PresentationOutcome {
    SHOWN,
    ALREADY_SHOWN,
    DISMISSED,
    EJECTED_TO_HOME,
    FAILED,
    STALE
}

/** Android-independent presentation adapter owned by the accessibility service. */
interface BlockingSurface {
    fun show(request: BlockRequest): PresentationOutcome
    fun dismiss()
    fun isAttached(): Boolean
}

fun interface GlobalActionAdapter {
    fun goHome(): Boolean
}

fun interface BlockingNotificationSink {
    fun postBlockFailure(message: String)
}

data class PackageLastUsed(val packageName: String, val lastTimeUsedMs: Long)

fun selectRecentForegroundPackage(
    candidates: List<PackageLastUsed>,
    selfPackage: String,
    nowMs: Long,
    maxAgeMs: Long
): String? {
    require(maxAgeMs >= 0L) { "Maximum foreground age must be non-negative" }
    return candidates
        .asSequence()
        .filter { it.packageName != selfPackage }
        .maxByOrNull { it.lastTimeUsedMs }
        ?.takeIf { nowMs - it.lastTimeUsedMs in 0L..maxAgeMs }
        ?.packageName
}

class ForegroundObservation {
    private var observedPackage: String? = null

    fun observe(packageName: String) {
        require(packageName.isNotBlank()) { "Observed package must not be blank" }
        observedPackage = packageName
    }

    fun currentOr(fallback: String?): String? = observedPackage ?: fallback

    fun current(): String? = observedPackage

    fun reconcile(resolvedPackage: String?, retainCurrent: Boolean): String? {
        if (!retainCurrent && resolvedPackage != null) observe(resolvedPackage)
        return observedPackage ?: resolvedPackage
    }

    fun clear() {
        observedPackage = null
    }
}

fun isRealSelfActivityEvent(
    eventPackage: String,
    eventClass: String?,
    selfPackage: String,
    mainActivityClass: String
): Boolean = eventPackage == selfPackage && eventClass == mainActivityClass

data class ForegroundToken(
    val generation: Long,
    val packageName: String,
    val domain: String?
)

/**
 * Prevents transition-generated launcher/system events from superseding a
 * real app before its asynchronous policy evaluation completes.
 */
class ForegroundTransitionGuard(private val graceMs: Long = 1_500L) {
    private var appPackage: String? = null
    private var appEventMs = Long.MIN_VALUE

    fun select(
        eventPkg: String,
        eventIsNeverBlock: Boolean,
        blocking: Boolean,
        focusedPkg: String?,
        nowMs: Long
    ): String {
        if (!eventIsNeverBlock) {
            appPackage = eventPkg
            appEventMs = nowMs
            return eventPkg
        }
        if (!blocking && nowMs - appEventMs < graceMs) {
            appPackage?.let { return it }
        }
        return focusedPkg ?: eventPkg
    }
}

/**
 * Serializes foreground generations and presentation lifecycle.
 *
 * All methods are expected to be called from one serialized executor (the
 * accessibility service's main thread in production). The class itself has no
 * Android dependencies, which keeps stale-result and lifecycle behavior JVM-testable.
 */
class BlockingController(
    private val surface: BlockingSurface,
    private val globalActions: GlobalActionAdapter,
    private val notifications: BlockingNotificationSink,
    private val performBack: () -> Boolean = { false }
) {
    private var generation = 0L
    private var foreground: ForegroundToken? = null
    private var attachedRequest: BlockRequest? = null
    private var awaitingHomeTransition = false
    private var destroyed = false

    fun foregroundChanged(packageName: String?, domain: String?): ForegroundToken? {
        if (destroyed) return null
        val normalizedPackage = packageName?.takeIf { it.isNotBlank() }
        if (foreground?.packageName == normalizedPackage && foreground?.domain == domain) {
            return foreground
        }
        generation++
        val previous = foreground
        val next = normalizedPackage?.let {
            ForegroundToken(generation, it, domain)
        }
        foreground = next

        if (awaitingHomeTransition && previous?.packageName != next?.packageName) {
            dismissAttached()
            awaitingHomeTransition = false
        }
        if (next == null && !awaitingHomeTransition) dismissAttached()
        return next
    }

    fun currentToken(): ForegroundToken? = foreground

    /** True only while the service owns a touch-consuming block surface. */
    fun isBlocking(): Boolean = !destroyed && surface.isAttached()

    fun isCurrent(token: ForegroundToken): Boolean = !destroyed && foreground == token

    /**
     * Applies one composite decision. App blocking takes precedence over web
     * blocking; null requests mean that the corresponding verdict allows.
     */
    fun present(
        token: ForegroundToken,
        appRequest: BlockRequest?,
        webRequest: BlockRequest?,
        webBack: Boolean = false
    ): PresentationOutcome {
        if (!isCurrent(token)) return PresentationOutcome.STALE
        if (awaitingHomeTransition) return PresentationOutcome.ALREADY_SHOWN
        if (webBack) {
            performBack()
            return PresentationOutcome.ALREADY_SHOWN
        }
        val request = appRequest ?: webRequest
        if (request == null) {
            val wasAttached = surface.isAttached() || attachedRequest != null
            dismissAttached()
            return if (wasAttached) PresentationOutcome.DISMISSED else PresentationOutcome.ALREADY_SHOWN
        }
        if (attachedRequest == request && surface.isAttached()) {
            return PresentationOutcome.ALREADY_SHOWN
        }

        awaitingHomeTransition = false
        val outcome = surface.show(request)
        return when (outcome) {
            PresentationOutcome.SHOWN, PresentationOutcome.ALREADY_SHOWN -> {
                attachedRequest = request
                outcome
            }
            PresentationOutcome.FAILED -> failClosed(request)
            else -> outcome
        }
    }

    /** Called by the overlay's Go home control. Removal waits for a real foreground transition. */
    fun goHome(): PresentationOutcome {
        if (destroyed) return PresentationOutcome.FAILED
        val success = try { globalActions.goHome() } catch (_: Exception) { false }
        return if (success) {
            awaitingHomeTransition = true
            PresentationOutcome.EJECTED_TO_HOME
        } else {
            notifications.postBlockFailure("Unable to leave blocked ${foreground?.packageName ?: "app"}")
            PresentationOutcome.FAILED
        }
    }

    /** Preserve the attached overlay where the platform permits it. */
    fun onServiceInterrupted() {
        // Accessibility overlays normally survive interruption. If the platform
        // removes it, reconnect causes the next foreground event to re-evaluate.
    }

    fun onServiceReconnected(): ForegroundToken? {
        if (destroyed) return null
        val old = foreground ?: return null
        generation++
        return old.copy(generation = generation).also { foreground = it }
    }

    fun onServiceDestroyed() {
        if (destroyed) return
        destroyed = true
        dismissAttached()
        foreground = null
    }

    private fun failClosed(request: BlockRequest): PresentationOutcome {
        attachedRequest = null
        val homeSucceeded = try { globalActions.goHome() } catch (_: Exception) { false }
        if (homeSucceeded) {
            // The overlay is not attached, so do not wait for a foreground
            // transition that may never arrive. A subsequent evaluation must
            // be able to retry the surface and/or Home action.
            awaitingHomeTransition = false
            notifications.postBlockFailure("${request.message} — returned Home because the block surface failed")
            return PresentationOutcome.EJECTED_TO_HOME
        }
        notifications.postBlockFailure("${request.message} — block surface and Home action failed for ${request.subject}")
        return PresentationOutcome.FAILED
    }

    private fun dismissAttached() {
        if (surface.isAttached() || attachedRequest != null) {
            surface.dismiss()
        }
        attachedRequest = null
    }
}
