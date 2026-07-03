package com.pcontrol.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Day-key helpers for per-day usage tracking. */
object UsageDay {

    /** Formats [dateTime] as a day key ("YYYY-MM-DD") in [zone]. */
    fun keyFrom(dateTime: ZonedDateTime, zone: ZoneId): String {
        val local = dateTime.withZoneSameInstant(zone)
        return local.toLocalDate().toString()
    }

    /** The current device-local day key. */
    fun currentKey(zone: ZoneId): String {
        return LocalDate.now(zone).toString()
    }
}
