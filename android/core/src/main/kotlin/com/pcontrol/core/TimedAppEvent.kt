package com.pcontrol.core

/**
 * A foreground-activity event with its wall-clock timestamp, used by
 * [UsageBackfill] to reconstruct usage over a period during which the
 * tracker was not running (or was frozen).
 *
 * Mirrors [AppEvent] plus the timestamp carried by the Android
 * `UsageEvents.Event.timeStamp`.
 */
data class TimedAppEvent(
    val packageName: String,
    val eventType: Int,
    val timestampMs: Long
)
