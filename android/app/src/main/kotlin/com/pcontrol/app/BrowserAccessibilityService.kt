package com.pcontrol.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pcontrol.core.DomainParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Per-browser foreground-session cache for the last successfully parsed domain.
 *
 * - `update(pkg, domain)`: sets the domain for the browser; null does NOT overwrite.
 * - `clear(pkg)`: clears the cached domain (when browser leaves foreground).
 * - `get(pkg)`: returns the last good domain, or null if never read.
 */
class BrowserDomainCache {
    private val cache = mutableMapOf<String, String>()

    /** Update the cache for [pkg] with [domain]. null values are ignored (keep last good). */
    fun update(pkg: String, domain: String?) {
        if (domain != null) {
            cache[pkg] = domain
        }
        // null domain does NOT overwrite — keeps the last successfully read domain
    }

    /** Clear the cached domain for [pkg] (called when browser leaves foreground). */
    fun clear(pkg: String) {
        cache.remove(pkg)
    }

    /** Get the last successfully parsed domain for [pkg], or null. */
    fun get(pkg: String): String? = cache[pkg]
}

/**
 * Accessibility service that:
 *
 * 1. **Reads the URL bar** of known browsers (updates [domainCache] when
 *    the URL bar content changes in a registered browser).
 *
 * 2. **Enforces app blocking** on [TYPE_WINDOW_STATE_CHANGED] events.
 *    When a non-browser app comes to the foreground, the service checks
 *    the cached policy and usage counters via [BlockingCoordinator]. If
 *    the app has exceeded its limit, [BlockedActivity] is launched
 *    immediately.
 *
 *    This is critical because the [TrackerService] tick loop can be
 *    throttled by OEM battery management (e.g. MIUI) when pcontrol is in
 *    the background. The accessibility service is system-bound and
 *    receives events regardless, so enforcement still fires.
 */
class BrowserAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BrowserAS"

        @Volatile
        var instance: BrowserAccessibilityService? = null

        /** Shared domain cache accessed by TrackerService. */
        val domainCache = BrowserDomainCache()
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var blockingCoordinator: BlockingCoordinator

    /** Last package that was evaluated, to avoid redundant enforcement calls. */
    private var lastCheckedPkg: String? = null

    override fun onServiceConnected() {
        Log.w(TAG, "onServiceConnected — accessibility service bound")
        instance = this
        blockingCoordinator = BlockingCoordinator(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.w(TAG, "onAccessibilityEvent called: eventIsNull=${event == null}")
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        Log.w(TAG, "event type=${event.eventType} pkg=$pkg class=${event.className}")

        // ── 1. Browser URL-bar reading (existing behaviour) ──────────
        val urlBarId = BrowserRegistry.urlBarViewId(pkg)
        if (urlBarId != null) {
            handleBrowserUrlBar(pkg, urlBarId)
            return
        }

        // ── 2. App-blocking enforcement (new) ────────────────────────
        // On TYPE_WINDOW_STATE_CHANGED, check if this app should be blocked.
        // Skip if this is the same package we just checked (debounce: avoids
        // redundant DB reads when a single app fires multiple state-changed
        // events for sub-activities).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != lastCheckedPkg) {
                lastCheckedPkg = pkg
                Log.w(TAG, "TYPE_WINDOW_STATE_CHANGED for $pkg — checking enforcement")
                scope.launch {
                    try {
                        val blocked = blockingCoordinator.checkAndEnforceApp(pkg)
                        if (blocked) {
                            Log.w(TAG, "Blocked app $pkg via accessibility event")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Enforcement check failed for $pkg", e)
                    }
                }
            }
        }
    }

    private fun handleBrowserUrlBar(pkg: String, urlBarId: String) {
        val root = rootInActiveWindow ?: return
        val urlNodes = root.findAccessibilityNodeInfosByViewId(urlBarId)
        root.recycle()

        if (urlNodes.isEmpty()) return

        val urlText = urlNodes[0].text?.toString()
        urlNodes.forEach { it.recycle() }

        val domain = urlText?.let { DomainParser.parse(it) }
        domainCache.update(pkg, domain)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        instance = null
    }
}
