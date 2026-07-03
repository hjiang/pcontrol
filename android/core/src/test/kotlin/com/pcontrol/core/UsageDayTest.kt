package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageDayTest {

    @Test
    fun `day key formats correctly from ZonedDateTime`() {
        val dt = ZonedDateTime.parse("2026-07-03T15:04:05+08:00")
        val key = UsageDay.keyFrom(dt, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-07-03", key)
    }

    @Test
    fun `day key handles UTC crossing`() {
        // 2026-07-03 01:00 UTC = 2026-07-03 09:00 Asia/Shanghai
        val dt = ZonedDateTime.parse("2026-07-03T01:00:00Z")
        val key = UsageDay.keyFrom(dt, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-07-03", key)
    }
}
