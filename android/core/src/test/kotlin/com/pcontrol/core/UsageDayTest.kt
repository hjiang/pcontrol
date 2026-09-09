package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageDayTest {

    // --- day key ---

    @Test
    fun `day key formats correctly from ZonedDateTime`() {
        val dt = ZonedDateTime.parse("2026-07-03T15:04:05+08:00")
        val key = UsageDay.keyFrom(dt, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-07-03", key)
    }

    @Test
    fun `day key handles UTC crossing`() {
        val dt = ZonedDateTime.parse("2026-07-03T01:00:00Z")
        val key = UsageDay.keyFrom(dt, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-07-03", key)
    }

    // --- merge counter ---

    @Test
    fun `merge counter without existing creates new`() {
        val counter = UsageDay.mergeCounter(
            existing = null,
            day = "2020-01-01",
            kind = "app",
            subject = "com.game",
            label = "Game",
            increment = 60
        )
        assertEquals("2020-01-01", counter.day)
        assertEquals("app", counter.kind)
        assertEquals("com.game", counter.subject)
        assertEquals("Game", counter.label)
        assertEquals(60, counter.seconds)
        assertEquals(0, counter.syncedSeconds)
        assertEquals(60, counter.unsyncedDelta)
    }

    @Test
    fun `merge counter adds to existing`() {
        val existing = UsageCounter(
            day = "2026-07-03",
            kind = "app",
            subject = "com.game",
            label = "Game",
            seconds = 120,
            syncedSeconds = 60
        )

        val merged = UsageDay.mergeCounter(
            existing = existing,
            day = "2026-07-03",
            kind = "app",
            subject = "com.game",
            label = "Game",
            increment = 30
        )
        assertEquals(150, merged.seconds)
        assertEquals(60, merged.syncedSeconds)
        assertEquals(90, merged.unsyncedDelta)
    }

    @Test
    fun `merge counter uses default label from subject when empty`() {
        val merged = UsageDay.mergeCounter(
            existing = null,
            day = "2020-01-01",
            kind = "web",
            subject = "youtube.com",
            label = "",
            increment = 10
        )
        assertEquals("youtube.com", merged.label)
    }

    @Test
    fun `merge counter preserves existing label`() {
        val existing = UsageCounter(
            day = "2026-07-03",
            kind = "app",
            subject = "com.game",
            label = "My Game",
            seconds = 10,
            syncedSeconds = 0
        )

        val merged = UsageDay.mergeCounter(
            existing = existing,
            day = "2026-07-03",
            kind = "app",
            subject = "com.game",
            label = "",
            increment = 10
        )
        assertEquals("My Game", merged.label)
    }

    @Test
    fun `merge counter without existing uses the caller day not today`() {
        // Regression (PR #69 review round 1): backfill merges slices for past
        // days; creating the row under LocalDate.now() booked past usage into
        // today and, via REPLACE, could wipe today's live-counted row and
        // reset syncedSeconds (duplicate server events).
        val merged = UsageDay.mergeCounter(
            existing = null,
            day = "2026-08-21",
            kind = "app",
            subject = "com.game",
            label = "Game",
            increment = 300
        )
        assertEquals("2026-08-21", merged.day)
        assertEquals(300, merged.seconds)
    }
}
