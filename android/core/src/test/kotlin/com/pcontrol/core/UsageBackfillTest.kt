package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class UsageBackfillTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val self = "com.pcontrol.app"
    private val cap = UsageBackfill.DEFAULT_SILENCE_CAP_MS

    private fun day(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    // ── plan ───────────────────────────────────────────────────────────

    @Test
    fun `plan returns null for zero cursor (fresh install)`() {
        assertNull(
            UsageBackfill.plan(
                cursorMs = 0L,
                nowMs = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `plan returns null when gap is below threshold`() {
        assertNull(
            UsageBackfill.plan(
                cursorMs = 1_700_000_000_000L,
                nowMs = 1_700_000_000_000L + UsageBackfill.MIN_GAP_MS - 1
            )
        )
    }

    @Test
    fun `plan returns window when gap meets threshold`() {
        val cursor = 1_700_000_000_000L
        val now = cursor + UsageBackfill.MIN_GAP_MS
        val window = UsageBackfill.plan(cursorMs = cursor, nowMs = now)
        assertEquals(cursor, window?.startMs)
        assertEquals(now, window?.endMs)
    }

    @Test
    fun `plan clamps an ancient cursor to the max window`() {
        val now = 1_700_000_000_000L
        val ancient = now - UsageBackfill.MAX_WINDOW_MS - 86_400_000L
        val window = UsageBackfill.plan(cursorMs = ancient, nowMs = now)
        assertEquals(now - UsageBackfill.MAX_WINDOW_MS, window?.startMs)
        assertEquals(now, window?.endMs)
    }

    @Test
    fun `plan returns null when cursor is in the future (clock skew)`() {
        val now = 1_700_000_000_000L
        assertNull(UsageBackfill.plan(cursorMs = now + 3_600_000L, nowMs = now))
    }

    // ── attribute ──────────────────────────────────────────────────────

    @Test
    fun `no events and no prior foreground attributes nothing`() {
        val slices = UsageBackfill.attribute(
            events = emptyList(),
            selfPackage = self,
            window = UsageBackfill.Window(0L, 3_600_000L),
            zone = zone
        )
        assertEquals(emptyList<UsageBackfill.Slice>(), slices)
    }

    @Test
    fun `single resume at window start attributes the tail capped at silence cap`() {
        val start = 1_700_000_000_000L
        val end = start + 3_600_000L // 1 h of silence — must cap at 5 min
        val slices = UsageBackfill.attribute(
            events = listOf(TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start)),
            selfPackage = self,
            window = UsageBackfill.Window(start, end),
            zone = zone
        )
        assertEquals(
            listOf(UsageBackfill.Slice(day(start), "com.game", (cap / 1000).toInt())),
            slices
        )
    }

    @Test
    fun `alternating apps attribute proportional durations`() {
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.game", AppEvent.ACTIVITY_PAUSED, start + 60_000),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, start + 60_000),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_PAUSED, start + 150_000),
                TimedAppEvent("com.launcher", AppEvent.ACTIVITY_RESUMED, start + 150_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 180_000),
            zone = zone
        )
        assertEquals(
            listOf(
                UsageBackfill.Slice(day(start), "com.game", 60),
                UsageBackfill.Slice(day(start), "com.video", 90),
                UsageBackfill.Slice(day(start), "com.launcher", 30)
            ),
            slices
        )
    }

    @Test
    fun `background transition stops attribution`() {
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.game", AppEvent.ACTIVITY_PAUSED, start + 30_000)
                // nothing resumed afterwards
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 600_000),
            zone = zone
        )
        assertEquals(listOf(UsageBackfill.Slice(day(start), "com.game", 30)), slices)
    }

    @Test
    fun `interval crossing midnight splits day keys`() {
        // game: [23:58→00:02) splits at midnight; video: [00:02→00:04)
        val t1 = Instant.parse("2023-11-14T23:58:00Z").toEpochMilli()
        val t2 = Instant.parse("2023-11-15T00:02:00Z").toEpochMilli()
        val end = Instant.parse("2023-11-15T00:04:00Z").toEpochMilli()
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, t1),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, t2)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(t1, end),
            zone = zone
        )
        assertEquals(
            listOf(
                UsageBackfill.Slice("2023-11-14", "com.game", 120),
                UsageBackfill.Slice("2023-11-15", "com.game", 120),
                UsageBackfill.Slice("2023-11-15", "com.video", 120)
            ),
            slices
        )
    }

    @Test
    fun `self package is never attributed`() {
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent(self, AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start + 60_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 120_000),
            zone = zone
        )
        assertEquals(
            listOf(UsageBackfill.Slice(day(start), "com.game", 60)),
            slices
        )
    }

    @Test
    fun `events before window start still establish the foreground`() {
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start - 45_000),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, start + 60_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 120_000),
            zone = zone
        )
        // game: [start, start+60s) — the pre-window event only seeds state
        assertEquals(
            listOf(
                UsageBackfill.Slice(day(start), "com.game", 60),
                UsageBackfill.Slice(day(start), "com.video", 60)
            ),
            slices
        )
    }

    @Test
    fun `frequent short events sum without cap loss`() {
        val start = 1_700_000_000_000L
        val events = buildList {
            add(TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start))
            for (i in 1..12) {
                val t = start + i * 10_000L
                add(
                    TimedAppEvent(
                        "com.game",
                        if (i % 2 == 1) AppEvent.ACTIVITY_PAUSED else AppEvent.ACTIVITY_RESUMED,
                        t
                    )
                )
            }
        }
        val end = start + 120_000
        val slices = UsageBackfill.attribute(
            events = events,
            selfPackage = self,
            window = UsageBackfill.Window(start, end),
            zone = zone
        )
        // 6 resumed intervals of 10 s, 20 s, 30 s, 40 s, 50 s, 60 s … capped at 10 s each
        assertEquals(listOf(UsageBackfill.Slice(day(start), "com.game", 60)), slices)
    }

    @Test
    fun `unsorted events are tolerated`() {
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, start + 60_000),
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 120_000),
            zone = zone
        )
        assertEquals(
            listOf(
                UsageBackfill.Slice(day(start), "com.game", 60),
                UsageBackfill.Slice(day(start), "com.video", 60)
            ),
            slices
        )
    }

    @Test
    fun `non-transition event types are ignored`() {
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.other", 19 /* USER_ACTIVITY */, start + 30_000),
                TimedAppEvent("com.other", 5 /* CONFIGURATION_CHANGE */, start + 45_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 60_000),
            zone = zone
        )
        assertEquals(listOf(UsageBackfill.Slice(day(start), "com.game", 60)), slices)
    }
}
