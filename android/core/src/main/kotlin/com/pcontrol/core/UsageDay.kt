package com.pcontrol.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A per-day usage counter row. */
data class UsageCounter(
    val day: String,       // "YYYY-MM-DD"
    val kind: String,      // "app" or "web"
    val subject: String,   // package name or registrable domain
    val label: String,     // human-readable name
    val seconds: Int,
    val syncedSeconds: Int
) {
    /** Returns the delta (unsynced seconds) to send to the server. */
    val unsyncedDelta: Int get() = seconds - syncedSeconds
}

/** Day-key helpers for per-day usage tracking. */
object UsageDay {

    /** Formats [dateTime] as a day key ("YYYY-MM-DD") in [zone]. */
    fun keyFrom(dateTime: ZonedDateTime, zone: ZoneId): String {
        val local = dateTime.withZoneSameInstant(zone)
        return local.toLocalDate().toString()
    }

    /** Formats [dateTime] as a day key ("YYYY-MM-DD") in a time zone. */
    fun keyFrom(dateTime: LocalDateTime, zone: ZoneId): String {
        val zdt = dateTime.atZone(zone)
        return zdt.toLocalDate().toString()
    }

    /** The current device-local day key. */
    fun currentKey(zone: ZoneId): String {
        return LocalDate.now(zone).toString()
    }

    /**
     * Merges [increment] seconds into an existing counter, or creates a new one.
     * The [existing] counter may be null (no prior row for this day/kind/subject).
     */
    fun mergeCounter(
        existing: UsageCounter?,
        kind: String,
        subject: String,
        label: String,
        increment: Int
    ): UsageCounter {
        val day = if (existing != null) existing.day
        else LocalDate.now().toString()

        val prevSeconds = existing?.seconds ?: 0
        // Keep the existing syncedSeconds, or initialize to 0
        val synced = existing?.syncedSeconds ?: 0

        return UsageCounter(
            day = day,
            kind = kind,
            subject = subject,
            label = label.ifEmpty { existing?.label ?: subject },
            seconds = prevSeconds + increment,
            syncedSeconds = synced
        )
    }
}
