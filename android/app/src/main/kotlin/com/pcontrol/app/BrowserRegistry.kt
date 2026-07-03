package com.pcontrol.app

/**
 * Maps known browser package names to their URL-bar view ID resource names.
 *
 * If a browser's URL bar can't be read, its time still counts as app time
 * but web-per-site tracking is unavailable.
 */
object BrowserRegistry {

    private val browsers = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
    )

    /** Returns the URL-bar view ID for a known browser, or null for unknown apps. */
    fun urlBarViewId(packageName: String): String? {
        return browsers[packageName]
    }

    /** Returns true if the package is a known browser. */
    fun isKnownBrowser(packageName: String): Boolean {
        return browsers.containsKey(packageName)
    }

    /** All known browser package names. */
    fun knownPackages(): Set<String> = browsers.keys
}
