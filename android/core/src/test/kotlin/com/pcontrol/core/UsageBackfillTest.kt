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
        // six resumed segments of 10 s each, separated by background gaps
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
    fun `user interaction event does not clear the foreground`() {
        // Real UsageEvents.Event.USER_INTERACTION = 7 — a touch on the
        // currently-foreground app must not read as a background transition.
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.game", 7 /* USER_INTERACTION */, start + 30_000),
                TimedAppEvent("com.game", 7 /* USER_INTERACTION */, start + 60_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 90_000),
            zone = zone
        )
        assertEquals(listOf(UsageBackfill.Slice(day(start), "com.game", 90)), slices)
    }

    @Test
    fun `system interaction event does not set the foreground`() {
        // Real UsageEvents.Event.SYSTEM_INTERACTION = 6 — not a resume.
        val start = 1_700_000_000_000L
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.system.thing", 6 /* SYSTEM_INTERACTION */, start + 30_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 60_000),
            zone = zone
        )
        assertEquals(listOf(UsageBackfill.Slice(day(start), "com.game", 60)), slices)
    }

    @Test
    fun `events after window end are clamped away`() {
        val start = 1_700_000_000_000L
        val end = start + 60_000
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, end + 120_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, end),
            zone = zone
        )
        assertEquals(listOf(UsageBackfill.Slice(day(start), "com.game", 60)), slices)
    }

    @Test
    fun `midnight split and transitions work across a DST fall-back boundary`() {
        // America/New_York on the fall-back day: 2026-11-01, 02:00 EDT
        // (06:00Z) → 01:00 EST — the 01:00–02:00 local hour occurs twice.
        // Attribution is epoch-based, so it must (a) attribute an interval
        // that straddles the repeated hour cleanly to its single local day,
        // and (b) split at the post-fall-back midnight of 11-02, which sits
        // at 05:00Z (25-hour day) rather than the usual 04:00Z.
        val ny = ZoneId.of("America/New_York")
        // 01:58 EDT on 11-01 — two minutes before the clocks fall back.
        val t1 = Instant.parse("2026-11-01T05:58:00Z").toEpochMilli()
        // 01:30 EST on 11-01 — after the fall-back, same local hour.
        val t2 = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        // 23:58 EST on 11-01 — five minutes before the (EST) midnight.
        val t3 = Instant.parse("2026-11-02T04:58:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-02T05:03:00Z").toEpochMilli()
        val slices = UsageBackfill.attribute(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, t1),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, t2),
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, t3)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(t1, end),
            zone = ny
        )
        // game: [01:58 EDT → capped 5 min = 01:03 EST) — straddles the
        // 02:00 fall-back, 300 s all on 11-01. video: [01:30 EST, capped
        // 5 min), 300 s on 11-01. game again: [23:58 → midnight → 00:03)
        // splits 120 s on 11-01 + 180 s on 11-02 at the 05:00Z midnight.
        assertEquals(
            listOf(
                UsageBackfill.Slice("2026-11-01", "com.game", 300 + 120),
                UsageBackfill.Slice("2026-11-01", "com.video", 300),
                UsageBackfill.Slice("2026-11-02", "com.game", 180)
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

    // ── attributeChunked ─────────────────────────────────────────────

    @Test
    fun `chunked attribution partitions the window and matches single-shot attribute`() {
        val start = 1_700_000_000_000L
        val end = start + 3 * UsageBackfill.DEFAULT_SILENCE_CAP_MS // 15 min
        val events = listOf(
            TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
            TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, start + 4 * 60_000),
            TimedAppEvent("com.video", AppEvent.ACTIVITY_PAUSED, start + 9 * 60_000),
            TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start + 10 * 60_000)
        )
        val window = UsageBackfill.Window(start, end)
        val chunks = UsageBackfill.attributeChunked(
            events = events,
            selfPackage = self,
            window = window,
            chunkMs = 4 * 60_000,
            zone = zone
        )
        // Chunks are contiguous and cover the window exactly.
        assertEquals(4, chunks.size)
        assertEquals(start, chunks.first().window.startMs)
        assertEquals(end, chunks.last().window.endMs)
        assertEquals(
            chunks.map { it.window.endMs }.dropLast(1),
            chunks.map { it.window.startMs }.drop(1)
        )
        // Summing the per-chunk slices equals the single-shot attribution.
        val expected = UsageBackfill.attribute(events, self, window, zone)
            .associate { (it.day to it.subject) to it.seconds }
        val actual = chunks.flatMap { it.slices }
            .groupBy({ it.day to it.subject }, { it.seconds })
            .mapValues { (_, seconds) -> seconds.sum() }
        assertEquals(expected, actual)
    }

    @Test
    fun `foreground time flows into the chunk it belongs to`() {
        val start = 1_700_000_000_000L
        val chunks = UsageBackfill.attributeChunked(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, start + 90_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 180_000),
            chunkMs = 60_000,
            zone = zone
        )
        // game owns [start, start+90s) split across chunks 1–2; video gets
        // the tail of chunk 2; chunk 3 is fully video.
        assertEquals(
            listOf(
                listOf(UsageBackfill.Slice(day(start), "com.game", 60)),
                listOf(
                    UsageBackfill.Slice(day(start), "com.game", 30),
                    UsageBackfill.Slice(day(start), "com.video", 30)
                ),
                listOf(UsageBackfill.Slice(day(start), "com.video", 60))
            ),
            chunks.map { it.slices }
        )
    }

    @Test
    fun `silence cap is measured from interval start across chunk boundaries`() {
        val start = 1_700_000_000_000L
        // One eventless 45-minute interval; 30-minute chunks. The 5-minute
        // cap runs from the interval start, so only the first chunk gets
        // time — chunking must not restart the cap (and inflate usage).
        val slices = UsageBackfill.attributeChunked(
            events = listOf(TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start)),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 45 * 60_000),
            chunkMs = 30 * 60_000,
            zone = zone
        )
        assertEquals(2, slices.size)
        assertEquals(
            listOf(UsageBackfill.Slice(day(start), "com.game", (cap / 1000).toInt())),
            slices[0].slices
        )
        assertEquals(emptyList<UsageBackfill.Slice>(), slices[1].slices)
    }

    @Test
    fun `pre-window seed event carries the foreground into the first chunk only`() {
        val start = 1_700_000_000_000L
        val chunks = UsageBackfill.attributeChunked(
            events = listOf(
                TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start - 600_000),
                TimedAppEvent("com.video", AppEvent.ACTIVITY_RESUMED, start + 30_000)
            ),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 90_000),
            chunkMs = 30_000,
            zone = zone
        )
        // game [start, start+30s) in chunk 1; video owns everything after.
        assertEquals(
            listOf(
                listOf(UsageBackfill.Slice(day(start), "com.game", 30)),
                listOf(UsageBackfill.Slice(day(start), "com.video", 30)),
                listOf(UsageBackfill.Slice(day(start), "com.video", 30))
            ),
            chunks.map { it.slices }
        )
    }

    @Test
    fun `retry from a progress frontier does not restart the silence cap`() {
        val start = 1_700_000_000_000L
        // One eventless hour, chunked at 30 min. The committed first chunk
        // already charged the 5-minute allowance from the interval's true
        // start; a retry from the progress frontier must not grant a fresh
        // allowance after the frontier.
        val chunks = UsageBackfill.attributeChunked(
            events = listOf(TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start)),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 3_600_000L),
            chunkMs = 30 * 60_000L,
            zone = zone,
            countFromMs = start + 30 * 60_000L
        )
        assertEquals(2, chunks.size)
        assertEquals(emptyList<UsageBackfill.Slice>(), chunks[0].slices)
        assertEquals(emptyList<UsageBackfill.Slice>(), chunks[1].slices)
    }

    @Test
    fun `retry from a progress frontier counts only the unconsumed remainder`() {
        val start = 1_700_000_000_000L
        // Game resumed 1 min before the frontier; the committed chunk charged
        // [29m, 30m) = 60 s of the 5-minute allowance. The retry may only add
        // [30m, 34m) = 240 s — measured from the true interval start, so the
        // total stays at the 300 s cap.
        val resumedAt = start + 29 * 60_000L
        val chunks = UsageBackfill.attributeChunked(
            events = listOf(TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, resumedAt)),
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 3_600_000L),
            chunkMs = 30 * 60_000L,
            zone = zone,
            countFromMs = start + 30 * 60_000L
        )
        assertEquals(2, chunks.size)
        assertEquals(emptyList<UsageBackfill.Slice>(), chunks[0].slices)
        assertEquals(
            listOf(UsageBackfill.Slice(day(start), "com.game", 240)),
            chunks[1].slices
        )
        assertEquals(
            (cap / 1000).toInt(),
            60 + chunks[1].slices.sumOf { it.seconds }
        )
    }

    @Test
    fun `sub-second remainders carry across chunk boundaries`() {
        val start = 1_700_000_000_000L
        val events = listOf(
            TimedAppEvent("com.game", AppEvent.ACTIVITY_RESUMED, start),
            TimedAppEvent("com.game", AppEvent.ACTIVITY_PAUSED, start + 2000L)
        )
        // game [0, 2s) split at 1.5s: independent per-chunk flooring would
        // emit 1 s + 0 s; the carry must emit 1 s + 1 s, matching the
        // single-shot floor(2000ms) = 2 s.
        val chunks = UsageBackfill.attributeChunked(
            events = events,
            selfPackage = self,
            window = UsageBackfill.Window(start, start + 3000L),
            chunkMs = 1500L,
            zone = zone
        )
        assertEquals(
            listOf(
                listOf(UsageBackfill.Slice(day(start), "com.game", 1)),
                listOf(UsageBackfill.Slice(day(start), "com.game", 1))
            ),
            chunks.map { it.slices }
        )
        assertEquals(
            listOf(UsageBackfill.Slice(day(start), "com.game", 2)),
            UsageBackfill.attribute(
                events = events,
                selfPackage = self,
                window = UsageBackfill.Window(start, start + 3000L),
                zone = zone
            )
        )
    }
}
