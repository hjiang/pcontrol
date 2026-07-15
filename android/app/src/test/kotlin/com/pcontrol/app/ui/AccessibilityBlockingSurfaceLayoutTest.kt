package com.pcontrol.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.AccessibilityBlockingContentRenderer
import com.pcontrol.app.BlockKind
import com.pcontrol.app.BlockRequest
import com.pcontrol.app.R
import com.pcontrol.app.blockingThemeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 6 tests for the layout and renderer used by the accessibility-owned
 * blocking surface. The service/window lifecycle is covered by the blocking
 * controller tests; these tests keep presentation independent of an Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AccessibilityBlockingSurfaceLayoutTest {

    private fun inflate(): View {
        val context = blockingThemeContext(
            ApplicationProvider.getApplicationContext(),
        )
        return LayoutInflater.from(context).inflate(R.layout.activity_blocked, null, false)
    }

    private fun render(
        subject: String = "com.example.app",
        message: String = "Daily limit reached",
        allowedSites: List<String> = emptyList(),
        onGoHome: () -> Unit = {},
    ): View = inflate().also { view ->
        AccessibilityBlockingContentRenderer.render(
            view,
            BlockRequest(
                kind = BlockKind.APP,
                subject = subject,
                message = message,
                allowedSites = allowedSites,
            ),
            onGoHome,
        )
    }

    @Test
    fun messageAndSubjectRenderInThemedHierarchy() {
        val view = render(subject = "com.example.game", message = "Time is up")

        assertEquals("Time is up", view.findViewById<TextView>(R.id.blocked_message).text.toString())
        assertEquals(
            "com.example.game",
            view.findViewById<TextView>(R.id.blocked_subject).text.toString(),
        )
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.blocked_subject_card).visibility)
        assertNotNull(view.findViewById<View>(R.id.blocked_shield))
        val background = view.background
        assertNotNull(background)
        if (background is android.graphics.drawable.ColorDrawable) {
            assertTrue(
                "background must not be saturated #FF0000",
                background.color != android.graphics.Color.RED,
            )
        }
    }

    @Test
    fun emptyAllowedSitesHideTheOptionalView() {
        val view = render()

        assertEquals(View.GONE, view.findViewById<View>(R.id.blocked_allowed_sites).visibility)
    }

    @Test
    fun allowedSitesRenderAsNonClickableVerticalList() {
        val view = render(allowedSites = listOf("schoolsite.com", "wikipedia.org"))
        val allowed = view.findViewById<TextView>(R.id.blocked_allowed_sites)

        assertEquals(View.VISIBLE, allowed.visibility)
        assertTrue(allowed.text.toString().contains("\n•\tschoolsite.com"))
        assertTrue(allowed.text.toString().contains("\n•\twikipedia.org"))
        assertFalse(allowed.text.toString().contains("schoolsite.com, wikipedia.org"))
        assertFalse(allowed.isClickable)
    }

    @Test
    fun goHomeControlInvokesCallback() {
        var calls = 0
        val view = render(onGoHome = { calls++ })

        assertTrue(view.findViewById<Button>(R.id.blocked_go_home).performClick())
        assertEquals(1, calls)
    }

    @Test
    fun contentOrderMatchesVisualAndTalkBackHierarchy() {
        val view = render(allowedSites = listOf("schoolsite.com"))
        val traversal = depthFirstIds(view)
        val expected = listOf(
            R.id.blocked_shield,
            R.id.blocked_message,
            R.id.blocked_subject,
            R.id.blocked_allowed_sites,
            R.id.blocked_reset,
            R.id.blocked_go_home,
        ).map(traversal::indexOf)

        assertTrue("every expected view must be present: $expected", expected.all { it >= 0 })
        assertEquals(expected.sorted(), expected)
    }

    private fun depthFirstIds(view: View): List<Int> = buildList {
        add(view.id)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                addAll(depthFirstIds(view.getChildAt(index)))
            }
        }
    }
}
