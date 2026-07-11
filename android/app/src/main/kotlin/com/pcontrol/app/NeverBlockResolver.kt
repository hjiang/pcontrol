package com.pcontrol.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import com.pcontrol.core.PolicyEngine

/**
 * Resolves every HOME-capable package plus the default dialer for the
 * fail-safe never-block set. A missing or ambiguous default HOME handler must
 * never make launchers blockable.
 */
object NeverBlockResolver {
    private const val CACHE_TTL_MS = 60_000L

    /** Conservative fallbacks, including the diagnosed Xiaomi launcher. */
    private val FALLBACK_LAUNCHERS = setOf(
        "com.android.launcher",
        "com.miui.home",
        "com.oneplus.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    @Volatile private var cachedSet: Set<String>? = null
    @Volatile private var cacheTimestamp = 0L

    fun resolve(context: Context): Set<String> {
        val cached = cachedSet
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        return buildSet(context).also {
            cachedSet = it
            cacheTimestamp = now
        }
    }

    private fun buildSet(context: Context): Set<String> {
        val set = PolicyEngine.BASE_NEVER_BLOCK_PACKAGES.toMutableSet()
        set += FALLBACK_LAUNCHERS
        set += resolveHomePackages(context)
        resolveDialer(context)?.let(set::add)
        return set
    }

    internal fun resolveHomePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            buildSet {
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.let(::add)
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapNotNullTo(this) { it.activityInfo?.packageName }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun resolveDialer(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } else null
    } catch (_: Exception) {
        null
    }
}
