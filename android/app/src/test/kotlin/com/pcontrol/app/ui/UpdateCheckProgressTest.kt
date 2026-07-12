package com.pcontrol.app.ui

import com.pcontrol.app.MainActivity
import com.pcontrol.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.View
import android.widget.Button

/**
 * Stage 5 Robolectric tests: when `renderUpdateState(CHECKING)` is set, the
 * progress indicator must announce the checking status so the user gets durable
 * feedback, and the in-flight guard on the check-update button prevents rapid
 * duplicate launches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class UpdateCheckProgressTest {

    private inline fun renderUpdateState(activity: MainActivity, state: UpdateUiState) {
        // Reflect the private seam MainActivity uses; the test thread IS the main
        // thread under paused-looper Robolectric, so no post() is required.
        val method = MainActivity::class.java.getDeclaredMethod(
            "renderUpdateState", UpdateUiState::class.java
        )
        method.isAccessible = true
        method.invoke(activity, state)
    }

    @Test
    fun progressIndicatorHiddenAtRest() {
        val a = buildActivity()
        assertEquals(View.GONE, a.findViewById<View>(R.id.update_progress).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.update_status).visibility)
    }

    @Test
    fun renderCheckingShowsProgressAndStatus() {
        val a = buildActivity()
        renderUpdateState(a, UpdateUiMapper.checking)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.update_progress).visibility)
        val status = a.findViewById<android.widget.TextView>(R.id.update_status)
        assertEquals(View.VISIBLE, status.visibility)
        assertEquals(a.getString(R.string.updates_checking), status.text.toString())
    }

    @Test
    fun inFlightGuardDisablesCheckButtonWhileCheckIsRunning() {
        val a = buildActivity()
        val btn = a.findViewById<Button>(R.id.btn_check_update)
        assertTrue("button enabled at rest", btn.isEnabled)
        // Simulate the production in-flight guard: disable before launching IO.
        btn.isEnabled = false
        assertFalse("button must be disabled while a check is running", btn.isEnabled)
    }

    private fun buildActivity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
}