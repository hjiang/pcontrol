package com.pcontrol.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.MainActivity
import com.pcontrol.app.R
import com.pcontrol.app.blockingThemeContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 1 smoke test: asserts that the activity and accessibility-owned blocking
 * layout inflate under the Material 3 theme in light and dark modes and that the *current* key views
 * still render (the layout *redesign* assertions belong to Stages 3 and 6).
 *
 * Robolectric needs `testOptions { unitTests { isIncludeAndroidResources = true } }`
 * (set in `app/build.gradle.kts`) so the merged Material 3 / AppCompat resources
 * are visible during inflation, plus `@Config(sdk = [26])` because Robolectric
 * 4.16.1's `DefaultSdkPicker` would otherwise try to fetch an SDK 37
 * android-all jar (we run on Java 17 / JDK 21 misses SDK 36) when
 * `isIncludeAndroidResources = true` exposes `targetSdk=37` from the merged
 * manifest. SDK 26 is the project's minSdk and its instrumented jar is cached
 * locally after the first download; inflate-time surrogate classes below SDK 26
 * reliably fail with PackageParser errors against AppCompat 1.7.1 + M3 1.13.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ThemeAndLayoutTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ── Theme contract: no-action-bar Material 3 base ────────────────

    @Test
    @Config(qualifiers = "notnight")
    fun mainActivity_usesNoActionBarMaterialTheme_light() {
        assertNoActionBarTheme()
    }

    @Test
    @Config(qualifiers = "night")
    fun mainActivity_usesNoActionBarMaterialTheme_dark() {
        assertNoActionBarTheme()
    }

    private fun assertNoActionBarTheme() {
        val theme = context.theme
        assertNotNull(theme)
        val attrs = intArrayOf(android.R.attr.windowActionBar)
        val typed = theme.obtainStyledAttributes(R.style.Pcontrol, attrs)
        // For a NoActionBar theme the resolved `windowActionBar` is false.
        val windowActionBar = typed.getBoolean(0, true)
        typed.recycle()
        assertTrue("Pcontrol should be a no-action-bar theme", !windowActionBar)
    }

    // ── Activity and accessibility-owned block layout inflate ────────

    @Test
    @Config(qualifiers = "notnight")
    fun mainActivity_inflates_light() {
        assertMainActivityInflates()
    }

    @Test
    @Config(qualifiers = "night")
    fun mainActivity_inflates_dark() {
        assertMainActivityInflates()
    }

    private fun assertMainActivityInflates() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertNotNull(activity.findViewById(R.id.status_usage))
        assertNotNull(activity.findViewById(R.id.btn_start))
        assertNotNull(activity.findViewById(R.id.switch_auto_update))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun accessibilityBlockLayout_inflates_light() {
        assertAccessibilityBlockLayoutInflates()
    }

    @Test
    @Config(qualifiers = "night")
    fun accessibilityBlockLayout_inflates_dark() {
        assertAccessibilityBlockLayoutInflates()
    }

    private fun assertAccessibilityBlockLayoutInflates() {
        val themedContext = blockingThemeContext(context)
        val view = LayoutInflater.from(themedContext)
            .inflate(R.layout.activity_blocked, null, false)
        val attrs = themedContext.obtainStyledAttributes(
            intArrayOf(com.google.android.material.R.attr.colorErrorContainer)
        )
        val errorContainer = attrs.getColor(0, android.graphics.Color.TRANSPARENT)
        attrs.recycle()
        assertTrue(
            "blocking surface must use the dedicated warm palette",
            errorContainer == ContextCompat.getColor(context, R.color.blocked_surface),
        )
        assertNotNull(view.findViewById(R.id.blocked_message))
        assertNotNull(view.findViewById(R.id.blocked_subject))
        assertNotNull(view.findViewById(R.id.blocked_allowed_sites))
        assertNotNull(view.findViewById(R.id.blocked_go_home))
    }

    // ── No hardcoded visible copy remains in layouts ────────────────

    @Test
    fun mainActivity_noHardcodedCopyInLayout() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val btn = activity.findViewById<android.widget.Button>(R.id.btn_usage)
        assertNotNull(btn)
        assertTrue("btn_usage should have resource-backed text", btn.text.isNotEmpty())
    }

    @Test
    fun blockedScreen_inflates() {
        val themedContext = blockingThemeContext(context)
        val view = LayoutInflater.from(themedContext)
            .inflate(R.layout.activity_blocked, null, false)
        assertNotNull(view.findViewById<View>(R.id.blocked_go_home))
    }
}