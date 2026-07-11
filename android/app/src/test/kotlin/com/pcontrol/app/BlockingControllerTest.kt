package com.pcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingControllerTest {
    @Test
    fun `only the real main activity counts as a self foreground event`() {
        assertTrue(isRealSelfActivityEvent(
            eventPackage = "com.pcontrol.app",
            eventClass = "com.pcontrol.app.MainActivity",
            selfPackage = "com.pcontrol.app",
            mainActivityClass = "com.pcontrol.app.MainActivity"
        ))
        assertFalse(isRealSelfActivityEvent(
            eventPackage = "com.pcontrol.app",
            eventClass = "android.widget.LinearLayout",
            selfPackage = "com.pcontrol.app",
            mainActivityClass = "com.pcontrol.app.MainActivity"
        ))
    }

    @Test
    fun `foreground transition guard keeps real app across immediate launcher noise`() {
        val guard = ForegroundTransitionGuard(graceMs = 1_500L)
        assertEquals(
            "com.tencent.mm",
            guard.select("com.tencent.mm", eventIsNeverBlock = false, blocking = false, focusedPkg = "com.miui.home", nowMs = 100L)
        )
        assertEquals(
            "com.tencent.mm",
            guard.select("com.miui.home", eventIsNeverBlock = true, blocking = false, focusedPkg = "com.miui.home", nowMs = 200L)
        )
        assertEquals(
            "com.miui.home",
            guard.select("com.miui.home", eventIsNeverBlock = true, blocking = true, focusedPkg = "com.miui.home", nowMs = 200L)
        )
        assertEquals(
            "com.miui.home",
            guard.select("com.miui.home", eventIsNeverBlock = true, blocking = false, focusedPkg = "com.miui.home", nowMs = 1_601L)
        )
    }

    private class FakeSurface : BlockingSurface {
        val shown = mutableListOf<BlockRequest>()
        var attached = false
        var showResult = PresentationOutcome.SHOWN
        var dismissCount = 0

        override fun show(request: BlockRequest): PresentationOutcome {
            if (showResult == PresentationOutcome.SHOWN) {
                attached = true
                shown += request
            }
            return showResult
        }

        override fun dismiss() {
            if (attached) dismissCount++
            attached = false
        }

        override fun isAttached(): Boolean = attached
    }

    private class FakeActions(var homeResult: Boolean = true) : GlobalActionAdapter {
        var homeCalls = 0
        override fun goHome(): Boolean {
            homeCalls++
            return homeResult
        }
    }

    private class FakeNotifications : BlockingNotificationSink {
        val messages = mutableListOf<String>()
        override fun postBlockFailure(message: String) { messages += message }
    }

    private val request = BlockRequest(
        kind = BlockKind.APP,
        subject = "com.example.game",
        message = "Game: limit reached",
        allowedSites = listOf("example.org")
    )

    @Test
    fun `blocked evaluation attaches one overlay and duplicate is already shown`() {
        val surface = FakeSurface()
        val controller = BlockingController(surface, FakeActions(), FakeNotifications())
        val token = controller.foregroundChanged("com.example.game", null)!!

        assertEquals(PresentationOutcome.SHOWN, controller.present(token, request, null))
        assertTrue(controller.isBlocking())
        assertEquals(PresentationOutcome.ALREADY_SHOWN, controller.present(token, request, null))
        assertEquals(1, surface.shown.size)
    }

    @Test
    fun `allowed foreground removes overlay once and blocked target reattaches`() {
        val surface = FakeSurface()
        val controller = BlockingController(surface, FakeActions(), FakeNotifications())
        val blocked = controller.foregroundChanged("com.example.game", null)!!
        controller.present(blocked, request, null)

        val allowed = controller.foregroundChanged("com.example.launcher", null)!!
        controller.present(allowed, null, null)
        assertEquals(1, surface.dismissCount)

        val blockedAgain = controller.foregroundChanged("com.example.game", null)!!
        assertEquals(PresentationOutcome.SHOWN, controller.present(blockedAgain, request, null))
        assertEquals(2, surface.shown.size)
    }

    @Test
    fun `stale evaluation cannot attach after foreground transition`() {
        val surface = FakeSurface()
        val controller = BlockingController(surface, FakeActions(), FakeNotifications())
        val stale = controller.foregroundChanged("com.example.game", null)!!
        val current = controller.foregroundChanged("com.example.launcher", null)!!

        assertEquals(PresentationOutcome.STALE, controller.present(stale, request, null))
        assertEquals(PresentationOutcome.ALREADY_SHOWN, controller.present(current, null, null))
        assertFalse(surface.attached)
    }

    @Test
    fun `stale web Back is never dispatched`() {
        val backs = intArrayOf(0)
        val controller = BlockingController(
            FakeSurface(), FakeActions(), FakeNotifications(), performBack = { backs[0]++; true }
        )
        val stale = controller.foregroundChanged("com.example.browser", "blocked.org")!!
        controller.foregroundChanged("com.example.launcher", null)

        assertEquals(PresentationOutcome.STALE, controller.present(stale, null, null, webBack = true))
        assertEquals(0, backs[0])
    }

    @Test
    fun `app block wins over web allow and web block wins over app allow`() {
        val surface = FakeSurface()
        val controller = BlockingController(surface, FakeActions(), FakeNotifications())
        val app = controller.foregroundChanged("com.example.browser", "allowed.org")!!
        val web = request.copy(kind = BlockKind.WEB, subject = "blocked.org")

        assertEquals(PresentationOutcome.SHOWN, controller.present(app, request, null))
        val next = controller.foregroundChanged("com.example.browser", "blocked.org")!!
        assertEquals(PresentationOutcome.SHOWN, controller.present(next, null, web))
        assertEquals(web, surface.shown.last())
    }

    @Test
    fun `failed overlay ejects home and reports failure when home also fails`() {
        val surface = FakeSurface().apply { showResult = PresentationOutcome.FAILED }
        val actions = FakeActions(homeResult = false)
        val notifications = FakeNotifications()
        val controller = BlockingController(surface, actions, notifications)
        val token = controller.foregroundChanged("com.example.game", null)!!

        assertEquals(PresentationOutcome.FAILED, controller.present(token, request, null))
        assertEquals(1, actions.homeCalls)
        assertTrue(notifications.messages.single().contains("com.example.game"))
    }

    @Test
    fun `go home waits for observed transition before removing overlay`() {
        val surface = FakeSurface()
        val actions = FakeActions()
        val controller = BlockingController(surface, actions, FakeNotifications())
        val token = controller.foregroundChanged("com.example.game", null)!!
        controller.present(token, request, null)

        assertEquals(PresentationOutcome.EJECTED_TO_HOME, controller.goHome())
        assertTrue(surface.attached)
        controller.foregroundChanged("com.android.launcher", null)
        assertFalse(surface.attached)
        assertEquals(1, surface.dismissCount)
    }

    @Test
    fun `interruption retains an attached blocking surface`() {
        val surface = FakeSurface()
        val controller = BlockingController(surface, FakeActions(), FakeNotifications())
        val token = controller.foregroundChanged("com.example.game", null)!!
        controller.present(token, request, null)

        controller.onServiceInterrupted()
        assertTrue(surface.attached)
        assertEquals(0, surface.dismissCount)
    }

    @Test
    fun `destroy removes attached surface exactly once`() {
        val surface = FakeSurface()
        val controller = BlockingController(surface, FakeActions(), FakeNotifications())
        val token = controller.foregroundChanged("com.example.game", null)!!
        controller.present(token, request, null)

        controller.onServiceDestroyed()
        controller.onServiceDestroyed()
        assertEquals(1, surface.dismissCount)
    }
}
