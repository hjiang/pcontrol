package com.pcontrol.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pcontrol.core.DomainParser

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
 * Accessibility service that reads the URL bar of known browsers.
 *
 * Updates [domainCache] with the parsed registrable domain whenever the
 * URL bar content changes in a registered browser.
 */
class BrowserAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BrowserAccessibilityService? = null

        /** Shared domain cache accessed by TrackerService. */
        val domainCache = BrowserDomainCache()
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        val urlBarId = BrowserRegistry.urlBarViewId(pkg) ?: return // not a known browser

        val root = rootInActiveWindow ?: return
        val urlNodes = root.findAccessibilityNodeInfosByViewId(urlBarId)
        root.recycle()

        if (urlNodes.isEmpty()) return

        val urlText = urlNodes[0].text?.toString()
        urlNodes.forEach { it.recycle() }

        // Parse domain and update cache (null does NOT overwrite)
        val domain = urlText?.let { DomainParser.parse(it) }
        domainCache.update(pkg, domain)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
