package com.pcontrol.app.ui

import android.view.View
import com.pcontrol.app.MainActivity
import com.pcontrol.app.R
import com.pcontrol.app.ui.CapabilityFacts
import com.pcontrol.app.ui.SetupUiState
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

    private fun buildActivity(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return activity
    }

    // ── Section headings present ─────────────────────────────────────

    @Test
    fun appBarPresent() {
        findViewById(R.id.app_bar, buildActivity())
    }

    @Test
    fun statusHeroSummarizesIncompleteSetup() {
        val activity = buildActivity()
        activity.renderSetupState(SetupUiState.build(CapabilityFacts()))
        val hero = activity.findViewById<android.widget.TextView>(R.id.status_hero)
        assertNotNull(hero)
        assertTrue(hero.text.toString().contains(activity.getString(R.string.hero_setup_needed)))
        val progressText = activity.resources.getQuantityString(R.plurals.hero_progress, 0, 0, 5)
        assertTrue("hero missing progress: '${hero.text}'", hero.text.toString().contains(progressText))
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

    @Test
    fun completeSetupRendersReadyHeroAndStableStartAction() {
        val activity = buildActivity()
        activity.renderSetupState(
            SetupUiState.build(
                CapabilityFacts(
                    usage = true,
                    accessibility = true,
                    notifications = true,
                    battery = true,
                    server = true,
                    updater = false,
                )
            )
        )

        assertTrue(activity.findViewById<android.widget.TextView>(R.id.status_hero).text
            .toString().contains(activity.getString(R.string.hero_ready)))
        val start = activity.findViewById<android.widget.Button>(R.id.btn_start)
        assertTrue(start.isEnabled)
        assertEquals(activity.getString(R.string.start_monitoring), start.text.toString())
    }

    // ── Logical view order matches visual/TalkBack order ─────────────

    @Test
    fun appBarBeforeStatusHeroBeforeSections() {
        val activity = buildActivity()
        val contentView = activity.findViewById<View>(android.R.id.content)
        val order = listOf(
            R.id.app_bar, R.id.status_hero,
            R.id.section_required, R.id.section_server, R.id.section_updates,
        ).map { viewPosition(contentView, it) }
        assertTrue("all ordered views must exist: $order", order.all { it >= 0 })
        assertEquals(order, order.sorted())
    }

    private fun viewPosition(root: View, id: Int): Int {
        var position = 0
        fun visit(view: View): Int? {
            if (view.id == id) return position
            position += 1
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return visit(root) ?: -1
    }

    // ── Bottom CTA visible (not clipped) ─────────────────────────────

    @Test
    fun startButtonVisible() {
        val activity = buildActivity()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_start).visibility)
    }

    @Test
    fun startButtonRemainsNamedStartMonitoringWhenSetupIsIncomplete() {
        val activity = buildActivity()
        val start = activity.findViewById<android.widget.Button>(R.id.btn_start)
        assertEquals(activity.getString(R.string.start_monitoring), start.text.toString())
    }

    private fun findViewById(id: Int, activity: MainActivity) {
        assertNotNull("view must exist in redesigned layout", activity.findViewById<View>(id))
    }
}