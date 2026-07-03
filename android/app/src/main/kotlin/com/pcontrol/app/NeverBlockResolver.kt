package com.pcontrol.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import com.pcontrol.core.PolicyEngine

/**
 * Resolves the default launcher and dialer at runtime so they can be
 * added to the never-block set (§6 rule 1).
 *
 * Caches the resolved set for 60 seconds; fallback includes the previously
 * hardcoded launchers (fail-safe: better to under-block the launcher than
 * to brick the phone).
 */
object NeverBlockResolver {

    private const val CACHE_TTL_MS = 60_000L

    /** Fallback launchers used when resolution fails. */
    private val FALLBACK_LAUNCHERS = setOf(
        "com.android.launcher",
        "com.oneplus.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
    )

    @Volatile
    private var cachedSet: Set<String>? = null

    @Volatile
    private var cacheTimestamp = 0L

    /**
     * Returns the full never-block set (base + resolved launcher + dialer).
     * Results are cached for [CACHE_TTL_MS] milliseconds.
     */
    fun resolve(context: Context): Set<String> {
        val cached = cachedSet
        val now = System.currentTimeMillis()
        if (cached != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cached
        }

        val result = buildSet(context)
        cachedSet = result
        cacheTimestamp = now
        return result
    }

    private fun buildSet(context: Context): Set<String> {
        val set = PolicyEngine.BASE_NEVER_BLOCK_PACKAGES.toMutableSet()

        // Resolve default launcher
        val launcher = resolveLauncher(context) ?: FALLBACK_LAUNCHERS.first()
        set.add(launcher)

        // Resolve default dialer
        val dialer = resolveDialer(context)
        if (dialer != null) set.add(dialer)

        return set
    }

    private fun resolveLauncher(context: Context): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            resolveInfo?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveDialer(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val telecomManager =
                    context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.defaultDialerPackage
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
