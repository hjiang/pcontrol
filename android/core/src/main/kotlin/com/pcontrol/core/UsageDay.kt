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
     * Merges [increment] seconds into an existing counter, or creates a new
     * one under [day]. The [existing] counter may be null (no prior row for
     * that day/kind/subject).
     *
     * The day of a created row comes from the caller — a backfill merge for
     * a past day must never be booked under today's key (which would, via
     * REPLACE upsert, overwrite and reset today's row).
     */
    fun mergeCounter(
        existing: UsageCounter?,
        day: String,
        kind: String,
        subject: String,
        label: String,
        increment: Int
    ): UsageCounter {
        val rowDay = if (existing != null) existing.day else day

        val prevSeconds = existing?.seconds ?: 0
        // Keep the existing syncedSeconds, or initialize to 0
        val synced = existing?.syncedSeconds ?: 0

        return UsageCounter(
            day = rowDay,
            kind = kind,
            subject = subject,
            label = label.ifEmpty { existing?.label ?: subject },
            seconds = prevSeconds + increment,
            syncedSeconds = synced
        )
    }
}
