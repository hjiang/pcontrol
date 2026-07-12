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
import com.pcontrol.app.update.UpdateResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun inFlightGuardPreventsDuplicateProductionChecks() {
        val a = buildActivity()
        val btn = a.findViewById<Button>(R.id.btn_check_update)
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        a.updateRunner = {
            calls.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            UpdateResult.UP_TO_DATE
        }

        a.checkForUpdates()
        assertTrue("runner should start", started.await(5, TimeUnit.SECONDS))
        assertFalse("button disabled while production check runs", btn.isEnabled)
        a.checkForUpdates()
        assertEquals("second tap must not invoke the runner", 1, calls.get())

        release.countDown()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun buildActivity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
}