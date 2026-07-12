package com.pcontrol.app

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.pcontrol.core.DomainParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Per-browser foreground-session cache for the last successfully parsed domain. */
class BrowserDomainCache {
    private val cache = ConcurrentHashMap<String, String>()
    fun update(pkg: String, domain: String?) { if (domain != null) cache[pkg] = domain }
    fun clear(pkg: String) { cache.remove(pkg) }
    fun get(pkg: String): String? = cache[pkg]
}

/**
 * Owns the accessibility overlay and the only controller allowed to mutate it.
 * Window events trigger immediate evaluation; TrackerService supplies periodic
 * evaluations after usage counters advance for a continuously foreground app.
 */
class BrowserAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "BrowserAS"
        private const val KEEP_ALIVE_RETRY_MS = 10_000L

        @Volatile
        var instance: BrowserAccessibilityService? = null

        val domainCache = BrowserDomainCache()

        private data class TrackerObservation(val pkg: String, val domain: String?)
        @Volatile private var latestTrackerObservation: TrackerObservation? = null

        /** Delivers a periodic evaluation after reconciling with the active root window. */
        fun submitTrackerEvaluation(pkg: String, domain: String?, evaluation: ForegroundEvaluation) {
            latestTrackerObservation = TrackerObservation(pkg, domain)
            instance?.applyTrackerEvaluation(pkg, domain, evaluation)
        }

        /** Main-thread root lookup for TrackerService's periodic fallback. */
        fun requestActiveForeground(callback: (String?) -> Unit) {
            val service = instance
            if (service == null) {
                callback(null)
            } else {
                service.mainHandler.post {
                    service.ensureKeepAlive()
                    callback(service.foregroundPackageForTracker())
                }
            }
        }

        fun notifyMainActivityForeground() {
            val service = instance ?: return
            service.mainHandler.post {
                if (instance === service) service.showMainActivityForeground()
            }
        }

        fun notifyMainActivityBackground() {
            val service = instance ?: return
            service.mainHandler.post {
                if (instance === service) service.mainActivityForeground = false
            }
        }
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var coordinator: BlockingCoordinator
    private lateinit var controller: BlockingController
    private lateinit var keepAliveController: AccessibilityKeepAliveController
    private var lastLoggedEvaluation: String? = null
    private var lastContentCheckPkg: String? = null
    private var lastContentCheckMs = 0L
    private val windowTitlePackages = ConcurrentHashMap<String, String>()
    private val windowIdPackages = ConcurrentHashMap<Int, String>()
    private val transitionGuard = ForegroundTransitionGuard()
    private val foregroundObservation = ForegroundObservation()
    private var mainActivityForeground = false
    private var keepAliveRetryScheduled = false
    private val keepAliveRetry = Runnable {
        keepAliveRetryScheduled = false
        ensureKeepAlive()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::controller.isInitialized) controller.onServiceDestroyed()
        if (::keepAliveController.isInitialized) keepAliveController.stop()
        mainHandler.removeCallbacks(keepAliveRetry)
        keepAliveRetryScheduled = false

        instance = this
        keepAliveController = AccessibilityKeepAliveController(AccessibilityKeepAliveOverlay(this))
        ensureKeepAlive()
        coordinator = BlockingCoordinator(this)
        controller = BlockingController(
            surface = AccessibilityBlockingSurface(this) { mainHandler.post { controller.goHome() } },
            globalActions = GlobalActionAdapter {
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            notifications = BlockingNotificationSink { message ->
                Enforcer.postBlockFailureNotification(this, message)
            },
            performBack = { performGlobalAction(GLOBAL_ACTION_BACK) }
        )
        latestTrackerObservation?.let { observeAndEvaluate(it.pkg, it.domain, 0) }
        mainHandler.postDelayed({
            activeRootPackage()?.takeUnless { it == packageName }?.let { pkg ->
                foregroundObservation.observe(pkg)
                observeAndEvaluate(pkg, domainCache.get(pkg), 0)
            }
        }, 300L)
        Log.i(TAG, "Accessibility service connected; blocking surface ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::controller.isInitialized) return
        ensureKeepAlive()
        val pkg = event.packageName?.toString() ?: return
        if (event.windowId >= 0) windowIdPackages[event.windowId] = pkg
        // Real MainActivity navigation is an ALLOW transition. Other self
        // events originate from the overlay/service and must not dismiss it.
        if (pkg == packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                isRealSelfActivityEvent(
                    eventPackage = pkg,
                    eventClass = event.className?.toString(),
                    selfPackage = packageName,
                    mainActivityClass = MainActivity::class.java.name
                )
            ) {
                showMainActivityForeground()
            }
            return
        }

        val urlBarId = BrowserRegistry.urlBarViewId(pkg)
        if (urlBarId != null) handleBrowserUrlBar(pkg, urlBarId)

        val focusedApplicationEvent = isFocusedApplicationWindow(event.windowId)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            focusedApplicationEvent && pkg != packageName
        ) {
            val blockedForeground = controller.currentToken()?.packageName
            if (controller.isBlocking() && pkg != blockedForeground) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (pkg != lastContentCheckPkg || now - lastContentCheckMs >= 2_000L) {
                lastContentCheckPkg = pkg
                lastContentCheckMs = now
                val effectivePkg = transitionGuard.select(
                    eventPkg = pkg,
                    eventIsNeverBlock = isTransientPackage(pkg),
                    blocking = controller.isBlocking(),
                    focusedPkg = pkg,
                    nowMs = now
                )
                foregroundObservation.observe(effectivePkg)
                observeAndEvaluate(effectivePkg, domainCache.get(effectivePkg), 0)
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(TAG, "Window-state event: pkg=$pkg class=${event.className}")
            val blockedForeground = controller.currentToken()?.packageName
            val focusedPkg = if (focusedApplicationEvent) {
                pkg
            } else {
                activeRootPackage()?.takeUnless { it == packageName }
            }
            if (controller.isBlocking() && pkg != blockedForeground) {
                // Attaching an accessibility overlay can itself produce a
                // launcher/system event. Only the focused application window
                // can confirm that the user actually navigated away.
                if (focusedPkg == null || focusedPkg == blockedForeground) {
                    Log.i(TAG, "Ignoring overlay-adjacent event $pkg while blocking $blockedForeground")
                    return
                }
            }

            // HyperOS emits System UI/Search/launcher events during an app
            // switch. Preserve the real app event briefly while its Room
            // evaluation completes; once blocked, focused Home wins immediately.
            val effectivePkg = transitionGuard.select(
                eventPkg = pkg,
                eventIsNeverBlock = isTransientPackage(pkg),
                blocking = controller.isBlocking(),
                focusedPkg = focusedPkg,
                nowMs = android.os.SystemClock.elapsedRealtime()
            )
            foregroundObservation.observe(effectivePkg)
            observeAndEvaluate(effectivePkg, domainCache.get(effectivePkg), 0)
        }
    }

    private fun showMainActivityForeground() {
        if (!::controller.isInitialized) return
        mainActivityForeground = true
        foregroundObservation.observe(packageName)
        val token = controller.foregroundChanged(packageName, null) ?: return
        controller.present(token, null, null)
    }

    private fun foregroundPackageForTracker(): String? {
        if (mainActivityForeground) return packageName
        if (controller.isBlocking()) return foregroundObservation.current()
        val focusedPackage = activeRootPackage()?.takeUnless { it == packageName }
        val recentPackage = if (focusedPackage == null) recentNonSelfUsagePackage() else null
        return foregroundObservation.reconcile(
            resolvedPackage = focusedPackage ?: recentPackage,
            retainCurrent = false
        )
    }

    private fun recentNonSelfUsagePackage(): String? {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return try {
            selectRecentForegroundPackage(
                candidates = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 120_000L,
                    now
                ).map { PackageLastUsed(it.packageName, it.lastTimeUsed) },
                selfPackage = packageName,
                nowMs = now,
                maxAgeMs = 30_000L
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to bootstrap foreground from recent usage", e)
            null
        }
    }

    /** Runs on the main thread to capture a generation before Room I/O. */
    private fun observeAndEvaluate(pkg: String, domain: String?, ticksWithoutDomain: Int) {
        val token = controller.foregroundChanged(pkg, domain) ?: return
        ioScope.launch {
            try {
                val evaluation = coordinator.evaluateForeground(pkg, domain, ticksWithoutDomain)
                mainHandler.post {
                    if (controller.isCurrent(token)) {
                        logEvaluation(pkg, evaluation)
                        val outcome = controller.present(
                            token, evaluation.appRequest, evaluation.webRequest, evaluation.webBack
                        )
                        logOutcome(pkg, outcome)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreground evaluation failed for $pkg", e)
            }
        }
    }

    /** Called by TrackerService after it has committed this tick's counters. */
    private fun applyTrackerEvaluation(pkg: String, domain: String?, evaluation: ForegroundEvaluation) {
        mainHandler.post {
            if (!::controller.isInitialized || pkg == packageName) return@post
            val observedPkg = foregroundObservation.current()
            if (observedPkg != null && observedPkg != pkg) return@post
            // UsageEvents can miss a resumed task on HyperOS. The periodic
            // path is authoritative only when the accessibility root agrees;
            // then it advances the same generation/controller as an event.
            val rootPkg = activeRootPackage()?.takeUnless { it == packageName }
            if (rootPkg != null && rootPkg != pkg) return@post
            val current = controller.currentToken()
            val token = if (current?.packageName == pkg && current.domain == domain) {
                current
            } else {
                controller.foregroundChanged(pkg, domain) ?: return@post
            }
            logEvaluation(pkg, evaluation)
            val outcome = controller.present(
                token, evaluation.appRequest, evaluation.webRequest, evaluation.webBack
            )
            logOutcome(pkg, outcome)
        }
    }

    private fun isTransientPackage(pkg: String): Boolean =
        pkg in NeverBlockResolver.resolve(this) ||
            pkg == "com.android.quicksearchbox" ||
            pkg == "com.miui.personalassistant"

    private fun isFocusedApplicationWindow(windowId: Int): Boolean {
        return windows.any { window ->
            window.id == windowId &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (window.isActive || window.isFocused)
        }
    }

    /** Returns the package backing AccessibilityService's focused/active window. */
    private fun activeRootPackage(): String? {
        // `rootInActiveWindow` can remain stale on HyperOS after a Recents
        // transition. The interactive-window list identifies the focused
        // WeChat task correctly on the diagnosed device.
        val applicationWindows = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val activeWindow = applicationWindows.firstOrNull { it.isFocused }
            ?: applicationWindows.firstOrNull { it.isActive }
            ?: return null
        val title = activeWindow.title?.toString()
        val cachedPackage = windowIdPackages[activeWindow.id]
            ?.takeUnless { it == packageName && !titleMatchesPackage(title, packageName) }
        if (cachedPackage != null) {
            activeWindow.recycle()
            return cachedPackage
        }

        val root = activeWindow.root
        val rootPackage = try {
            root?.packageName?.toString()
        } finally {
            root?.recycle()
            activeWindow.recycle()
        }
        if (rootPackage != null && rootPackage != packageName) return rootPackage
        title?.let { resolveWindowTitlePackage(it) }?.let { return it }
        // A self root for a differently titled focused window is known-bad.
        return rootPackage?.takeIf { titleMatchesPackage(title, it) }
    }

    /**
     * HyperOS may return this service's root node for another app's focused
     * window. Match that window's app-label title against packages visible in
     * UsageStats; results are cached and no QUERY_ALL_PACKAGES access is used.
     */
    private fun resolveWindowTitlePackage(title: String): String? {
        val normalizedTitle = normalizeWindowTitle(title)
        windowTitlePackages[normalizedTitle]?.let { return it }
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return try {
            val candidates = buildSet {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcherIntent, 0).forEach { info ->
                    add(info.activityInfo.packageName)
                }
                manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 7 * 86_400_000L,
                    now
                ).forEach { add(it.packageName) }
                addAll(windowIdPackages.values)
            }
            val matches = candidates.filter { titleMatchesPackage(title, it) }
            matches.singleOrNull()?.also { windowTitlePackages[normalizedTitle] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve focused window title '$title'", e)
            null
        }
    }

    private fun titleMatchesPackage(title: String?, candidate: String): Boolean {
        if (title == null) return false
        return try {
            val appInfo = packageManager.getApplicationInfo(candidate, 0)
            normalizeWindowTitle(packageManager.getApplicationLabel(appInfo).toString()) ==
                normalizeWindowTitle(title)
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeWindowTitle(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun handleBrowserUrlBar(pkg: String, urlBarId: String) {
        val root = rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByViewId(urlBarId)
        root.recycle()
        if (nodes.isEmpty()) return
        val domain = nodes.first().text?.toString()?.let(DomainParser::parse)
        nodes.forEach { it.recycle() }
        val oldDomain = domainCache.get(pkg)
        domainCache.update(pkg, domain)
        if (domain != null && domain != oldDomain && ::controller.isInitialized &&
            controller.currentToken()?.packageName == pkg
        ) {
            // Do not dismiss until the complete decision for the new domain is known.
            observeAndEvaluate(pkg, domain, 0)
        }
    }

    private fun ensureKeepAlive() {
        if (!::keepAliveController.isInitialized || keepAliveController.start()) return
        if (!keepAliveRetryScheduled) {
            keepAliveRetryScheduled = true
            mainHandler.postDelayed(keepAliveRetry, KEEP_ALIVE_RETRY_MS)
            Log.w(TAG, "Accessibility keep-alive unavailable; retry scheduled")
        }
    }

    override fun onInterrupt() {
        if (::controller.isInitialized) controller.onServiceInterrupted()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(keepAliveRetry)
        keepAliveRetryScheduled = false
        if (::controller.isInitialized) controller.onServiceDestroyed()
        if (::keepAliveController.isInitialized) keepAliveController.stop()
        foregroundObservation.clear()
        ioScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Release diagnostics only emit when the effective decision changes. */
    private fun logEvaluation(pkg: String, evaluation: ForegroundEvaluation) {
        val state = "$pkg app=${evaluation.appVerdict} web=${evaluation.webVerdict} " +
            "appSurface=${evaluation.appRequest != null} webSurface=${evaluation.webRequest != null} " +
            "webBack=${evaluation.webBack}"
        if (state != lastLoggedEvaluation) {
            lastLoggedEvaluation = state
            Log.i(TAG, "Foreground enforcement: $state")
        }
    }

    private fun logOutcome(pkg: String, outcome: PresentationOutcome) {
        if (outcome == PresentationOutcome.FAILED || outcome == PresentationOutcome.EJECTED_TO_HOME) {
            Log.w(TAG, "Blocking presentation for $pkg: $outcome")
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, "Blocking presentation for $pkg: $outcome")
        }
    }
}
