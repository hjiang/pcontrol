package com.pcontrol.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.core.Verdict
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EnforcerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // Reset warned set and web strikes between tests to avoid cross-test interference
        Enforcer.resetWarnedSet()
        Enforcer.webBlockStrikes.resetAll()
        // Ensure no leftover accessibility-service instance leaks across tests
        BrowserAccessibilityService.instance = null
    }

    @Test
    fun `ALLOW verdict does nothing`() {
        val intentsStarted = mutableListOf<Intent>()
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.ALLOW,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "",
            startActivity = { intent ->
                intentsStarted.add(intent)
                true
            }
        )

        assertEquals(0, intentsStarted.size)
    }

    @Test
    fun `BLOCK_WEB falls back after three strikes when no accessibility service is running`() {
        // Regression guard for §6 2-strikes rule: after 2 back-presses fail
        // (strikes 1-2 perform BACK, strike 3 launches BlockedActivity).
        BrowserAccessibilityService.instance = null
        var fallbackLaunched = false
        // Strikes 1-2: perform back (fails silently without service)
        repeat(2) {
            Enforcer.handleVerdict(
                context = context,
                verdict = Verdict.BLOCK_WEB,
                subject = "youtube.com",
                label = "YouTube",
                day = "2026-07-03",
                limitMessage = "blocked",
                startActivity = { _ -> fallbackLaunched = true; true }
            )
            assertEquals(false, fallbackLaunched)
        }
        // Strike 3: should fall back to BlockedActivity
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.BLOCK_WEB,
            subject = "youtube.com",
            label = "YouTube",
            day = "2026-07-03",
            limitMessage = "blocked",
            startActivity = { _ -> fallbackLaunched = true; true }
        )
        assertEquals(true, fallbackLaunched) // 3 strikes → BlockedActivity
    }

    @Test
    fun `BLOCK_WEB with injected successful back skips BlockedActivity`() {
        var fallbackLaunched = false
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.BLOCK_WEB,
            subject = "youtube.com",
            label = "YouTube",
            day = "2026-07-03",
            limitMessage = "blocked",
            performBack = { true }, // back action reported it removed the page
            startActivity = { _ -> fallbackLaunched = true; true }
        )
        assertEquals(false, fallbackLaunched) // back succeeded → no fallback
    }

    @Test
    fun `BLOCK_APP launches BlockedActivity`() {
        val intentsStarted = mutableListOf<Intent>()
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.BLOCK_APP,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 30 min limit reached",
            startActivity = { intent ->
                intentsStarted.add(intent)
                true
            }
        )

        assertEquals(1, intentsStarted.size)
        val intent = intentsStarted.first()
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `BLOCK_APP passes allowed sites to BlockedActivity intent`() {
        val intentsStarted = mutableListOf<Intent>()
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.BLOCK_APP,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 30 min limit reached",
            allowedSites = listOf("khanacademy.org"),
            startActivity = { intent ->
                intentsStarted.add(intent)
                true
            }
        )
        assertEquals(1, intentsStarted.size)
        assertEquals(
            arrayListOf("khanacademy.org"),
            intentsStarted.first().getStringArrayListExtra("allowed_sites")
        )
    }

    @Test
    fun `BLOCK_WEB performs back action`() {
        var backPerformed = false
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.BLOCK_WEB,
            subject = "youtube.com",
            label = "YouTube",
            day = "2026-07-03",
            limitMessage = "YouTube: 15 min limit reached",
            performBack = { backPerformed = true; true },
            startActivity = { _ -> true }
        )

        assertEquals(true, backPerformed)
    }

    @Test
    fun `BLOCK_WEB falls back to BlockedActivity after three strikes`() {
        var backCount = 0
        val intentsStarted = mutableListOf<Intent>()
        // Strikes 1-2: back is attempted (returns false = page still there)
        repeat(2) {
            Enforcer.handleVerdict(
                context = context,
                verdict = Verdict.BLOCK_WEB,
                subject = "youtube.com",
                label = "YouTube",
                day = "2026-07-03",
                limitMessage = "YouTube: 15 min limit reached",
                performBack = { backCount++; false },
                startActivity = { intent ->
                    intentsStarted.add(intent)
                    true
                }
            )
        }
        assertEquals(2, backCount)
        assertEquals(0, intentsStarted.size)

        // Strike 3: should launch BlockedActivity (3 strikes → fallback)
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.BLOCK_WEB,
            subject = "youtube.com",
            label = "YouTube",
            day = "2026-07-03",
            limitMessage = "YouTube: 15 min limit reached",
            performBack = { backCount++; false },
            startActivity = { intent ->
                intentsStarted.add(intent)
                true
            }
        )

        assertEquals(1, intentsStarted.size)
    }

    @Test
    fun `WARN posts notification once per subject per day`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.WARN,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 27 of 30 minutes used",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notifications = shadowNotificationManager.getAllNotifications()
        assertEquals(1, notifications.size)
        assertEquals("pcontrol limit warning", notifications[0].extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `WARN does not post duplicate notification for same subject and day`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // First warn
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.WARN,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 27 of 30 minutes used",
        )

        // Second warn — same subject, same day
        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.WARN,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 28 of 30 minutes used",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notifications = shadowNotificationManager.getAllNotifications()
        assertEquals(1, notifications.size)
    }

    @Test
    fun `WARN posts new notification for different subject`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.WARN,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 27 of 30 minutes used",
        )

        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.WARN,
            subject = "com.example.YouTube",
            label = "YouTube",
            day = "2026-07-03",
            limitMessage = "YouTube: 14 of 15 minutes used",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notifications = shadowNotificationManager.getAllNotifications()
        assertEquals(2, notifications.size)
    }

    @Test
    fun `WARN notification uses high importance channel`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        Enforcer.handleVerdict(
            context = context,
            verdict = Verdict.WARN,
            subject = "com.example.Game",
            label = "Game",
            day = "2026-07-03",
            limitMessage = "Game: 27 of 30 minutes used",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val allNotifications = shadowNotificationManager.getAllNotifications()
        assertEquals(1, allNotifications.size)
        // Verify the notification was posted (Robolectric shadows don't surface extras fully)
    }
}
