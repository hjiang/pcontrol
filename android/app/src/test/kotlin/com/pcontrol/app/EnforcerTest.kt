package com.pcontrol.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.core.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EnforcerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        Enforcer.resetWarnedSet()
        Enforcer.webBlockStrikes.resetAll()
        BrowserAccessibilityService.instance = null
    }

    @Test
    fun `BLOCK_APP returns an accessibility surface request without an activity launch`() {
        val action = Enforcer.handleVerdict(
            context, Verdict.BLOCK_APP, "com.example.game", "Game", "2026-07-03",
            "Game: limit reached", allowedSites = listOf("khanacademy.org")
        )

        val show = action as EnforcementAction.Show
        assertEquals(BlockKind.APP, show.request.kind)
        assertEquals("com.example.game", show.request.subject)
        assertEquals(listOf("khanacademy.org"), show.request.allowedSites)
    }

    @Test
    fun `BLOCK_WEB uses back twice then returns the common surface request`() {
        repeat(2) {
            assertEquals(
                EnforcementAction.Back,
                Enforcer.handleVerdict(
                    context, Verdict.BLOCK_WEB, "youtube.com", "YouTube", "2026-07-03", "blocked"
                )
            )
        }
        val action = Enforcer.handleVerdict(
            context, Verdict.BLOCK_WEB, "youtube.com", "YouTube", "2026-07-03", "blocked"
        ) as EnforcementAction.Show
        assertEquals(BlockKind.WEB, action.request.kind)
        assertEquals("youtube.com", action.request.subject)
    }

    @Test
    fun `ALLOW does nothing`() {
        assertEquals(
            EnforcementAction.None,
            Enforcer.handleVerdict(context, Verdict.ALLOW, "com.example.game", "Game", "2026-07-03", "")
        )
    }

    @Test
    fun `WARN posts once per subject per day`() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        Enforcer.handleVerdict(context, Verdict.WARN, "com.example.game", "Game", "2026-07-03", "warning")
        Enforcer.handleVerdict(context, Verdict.WARN, "com.example.game", "Game", "2026-07-03", "warning")
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `block request requires a subject and message`() {
        try {
            BlockRequest(BlockKind.APP, "", "message")
            throw AssertionError("expected invalid request to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue(true)
    }
}
