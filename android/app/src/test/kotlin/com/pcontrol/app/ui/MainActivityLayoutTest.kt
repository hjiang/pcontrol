package com.pcontrol.app.ui

import android.view.View
import com.pcontrol.app.MainActivity
import com.pcontrol.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 3 tests: the redesigned `MainActivity` layout (Section 4.2 of plan 09).
 *
 * Written against the redesigned XML hierarchy before it exists, so it must
 * fail as expected. Run under SDK 26 with `isIncludeAndroidResources = true`
 * (see `app/build.gradle.kts` and `app/src/test/resources/robolectric.properties`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MainActivityLayoutTest {

    private fun buildActivity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).create().get()

    // ── Section headings present ─────────────────────────────────────

    @Test
    fun appBarPresent() {
        findViewById(R.id.app_bar, buildActivity())
    }

    @Test
    fun statusHeroPresent() {
        findViewById(R.id.status_hero, buildActivity())
    }

    @Test
    fun requiredSectionHeadingPresent() {
        findViewById(R.id.section_required, buildActivity())
    }

    @Test
    fun serverSectionHeadingPresent() {
        findViewById(R.id.section_server, buildActivity())
    }

    @Test
    fun updatesSectionHeadingPresent() {
        findViewById(R.id.section_updates, buildActivity())
    }

    // ── Start button enabled state (no required permission granted) ─

    @Test
    fun startButtonDisabledWhenNothingGranted() {
        val activity = buildActivity()
        // Robolectric defaults => no permission granted, no server configured.
        val start = activity.findViewById<android.widget.Button>(R.id.btn_start)
        assertNotNull(start)
        assertFalse("start must be disabled until required setup is complete", start.isEnabled)
    }

    // ── Optional updater labeling is clearly marked optional ─────────

    @Test
    fun updatesSectionTextLabelsItAsOptional() {
        val activity = buildActivity()
        val heading = activity.findViewById<android.widget.TextView>(R.id.section_updates)
        assertNotNull(heading)
        val text = heading.text.toString()
        assertTrue("updates section should say optional in: $text", text.contains("optional", ignoreCase = true))
    }

    @Test
    fun updaterTitleIsDistinctFromRequiredTitles() {
        val activity = buildActivity()
        val updater = activity.findViewById<android.widget.TextView>(R.id.status_updater)
        assertNotNull(updater)
        // The optional updater is labeled with the "Install unknown apps" title.
        val text = updater.text.toString()
        assertTrue("updater status should mention install unknown apps: $text",
            text.contains("Install unknown apps", ignoreCase = true))
    }

    // ── Logical view order matches visual/TalkBack order ─────────────

    @Test
    fun appBarBeforeStatusHeroBeforeSections() {
        val activity = buildActivity()
        val appBarY = activity.findViewById<View>(R.id.app_bar).top
        val heroY = activity.findViewById<View>(R.id.status_hero).top
        val secReqY = activity.findViewById<View>(R.id.section_required).top
        val secSrvY = activity.findViewById<View>(R.id.section_server).top
        val secUpdY = activity.findViewById<View>(R.id.section_updates).top
        // Roughly: each successive element should be below the previous one in
        // XML ordering. We assert by hierarchy traversal position instead.
        val contentView = activity.findViewById<View>(android.R.id.content)
        val order = listOf(
            R.id.app_bar, R.id.status_hero,
            R.id.section_required, R.id.section_server, R.id.section_updates,
        ).map { viewPosition(contentView, it) }
        assertEquals(order, order.sorted())
    }

    private fun viewPosition(root: View, id: Int): Int {
        if (root.id == id) return 0
        if (root !is android.view.ViewGroup) return -1
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.id == id) return i
        }
        return -1
    }

    // ── Bottom CTA visible (not clipped) ─────────────────────────────

    @Test
    fun startButtonVisible() {
        val activity = buildActivity()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_start).visibility)
    }

    private fun findViewById(id: Int, activity: MainActivity) {
        assertNotNull("view must exist in redesigned layout", activity.findViewById<View>(id))
    }
}