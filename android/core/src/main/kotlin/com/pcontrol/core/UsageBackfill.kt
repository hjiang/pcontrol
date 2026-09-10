package com.pcontrol.core

import java.time.Instant
import java.time.ZoneId

/**
 * Reconstructs app usage for a period the live 10-second tick loop missed:
 * a process death (FGS timeout crash, low-memory kill, boot) or a HyperOS
 * Greeze freeze.
 *
 * The system-side UsageStats service keeps recording foreground
 * transitions while pcontrol is down, so the gap can be attributed after
 * the fact by replaying those transitions and charging each eventless
 * interval to the app that was foreground at its start.
 *
 * Because a locked screen keeps the last activity technically "resumed"
 * for hours, each eventless interval is capped at [DEFAULT_SILENCE_CAP_MS]
 * — an overnight screen-off must never be reported as hours of usage.
 */
object UsageBackfill {

    /** Max attributed duration for a single eventless interval. */
    const val DEFAULT_SILENCE_CAP_MS: Long = 5 * 60_000L

    /** Gaps below this are ignored (tick jitter). */
    const val MIN_GAP_MS: Long = 2 * 60_000L

    /**
     * Windows are clamped to at most this span (system event retention and
     * sanity): for an ancient cursor only the portion older than this is
     * dropped — the most recent span is still backfilled.
     */
    const val MAX_WINDOW_MS: Long = 7L * 24 * 60 * 60_000L

    /**
     * How far before the window start to query UsageEvents so the app that
     * was foreground when tracking stopped seeds the replay (the leading
     * interval [window.startMs, first in-window transition) is otherwise
     * unattributable). Phantom attribution is bounded by the silence cap.
     */
    const val SEED_LOOKBACK_MS: Long = 6 * 60 * 60_000L

    /** A backfill query window, in wall-clock epoch milliseconds. */
    data class Window(val startMs: Long, val endMs: Long)

    /** Attributed usage for one day/subject, in whole seconds. */
    data class Slice(val day: String, val subject: String, val seconds: Int)

    private val TRANSITION_TYPES = setOf(
        AppEvent.ACTIVITY_RESUMED,
        AppEvent.ACTIVITY_PAUSED
    )

    /**
     * Decides whether the persisted tick cursor indicates a reportable gap.
     *
     * Preconditions: `cursorMs` is a wall-clock epoch millisecond value
     * persisted by the tick loop (0 when never set); `nowMs >= cursorMs`.
     * Postcondition: returns null for fresh installs, sub-[MIN_GAP_MS]
     * gaps, and windows that clamp below [MIN_GAP_MS]; otherwise a window
     * whose start is clamped to at most [MAX_WINDOW_MS] ago.
     */
    fun plan(cursorMs: Long, nowMs: Long): Window? {
        if (cursorMs <= 0L) return null
        if (cursorMs > nowMs) return null // clock skew: cursor from a future RTC
        if (nowMs - cursorMs < MIN_GAP_MS) return null
        val startMs = maxOf(cursorMs, nowMs - MAX_WINDOW_MS)
        if (nowMs - startMs < MIN_GAP_MS) return null
        return Window(startMs, nowMs)
    }

    /**
     * Attributes the foreground time inside [window] implied by
     * chronologically ordered [events] (unordered input is tolerated — it
     * is sorted defensively).
     *
     * Invariants:
     * - the self package is never attributed;
     * - no eventless interval contributes more than [silenceCapMs];
     * - attribution is split at local midnights so day keys match the
     *   live tick path's device-local convention;
     * - intervals with no known foreground (background state) attribute
     *   nothing.
     */
    fun attribute(
        events: List<TimedAppEvent>,
        selfPackage: String,
        window: Window,
        zone: ZoneId,
        silenceCapMs: Long = DEFAULT_SILENCE_CAP_MS
    ): List<Slice> {
        val acc = LinkedHashMap<Pair<String, String>, Long>()
        var foreground: String? = null
        var cursor = window.startMs

        for (event in events.sortedBy { it.timestampMs }) {
            if (event.eventType !in TRANSITION_TYPES) continue
            val ts = event.timestampMs.coerceIn(window.startMs, window.endMs)
            if (ts > cursor) {
                attributeInterval(acc, foreground, cursor, ts, selfPackage, zone, silenceCapMs)
                cursor = ts
            }
            foreground = applyTransition(foreground, event)
        }
        if (window.endMs > cursor) {
            attributeInterval(acc, foreground, cursor, window.endMs, selfPackage, zone, silenceCapMs)
        }

        return acc.map { (dayAndSubject, ms) ->
            Slice(dayAndSubject.first, dayAndSubject.second, (ms / 1000L).toInt())
        }
    }

    /**
     * Charges [fromMs, toMs) to [pkg], capped at [silenceCapMs] measured
     * from the interval start (activity was provable at the start; long
     * silence implies the screen went off).
     */
    private fun attributeInterval(
        acc: MutableMap<Pair<String, String>, Long>,
        pkg: String?,
        fromMs: Long,
        toMs: Long,
        selfPackage: String,
        zone: ZoneId,
        silenceCapMs: Long
    ) {
        if (pkg == null || pkg == selfPackage || toMs <= fromMs) return
        val cappedEnd = minOf(toMs, fromMs + silenceCapMs)
        var t = fromMs
        while (t < cappedEnd) {
            val segmentEnd = minOf(cappedEnd, nextMidnightMs(t, zone))
            if (segmentEnd <= t) break // DST anomaly guard
            val key = dayKey(t, zone) to pkg
            acc[key] = (acc[key] ?: 0L) + (segmentEnd - t)
            t = segmentEnd
        }
    }

    private fun applyTransition(current: String?, event: TimedAppEvent): String? =
        when (event.eventType) {
            AppEvent.ACTIVITY_RESUMED -> event.packageName
            AppEvent.ACTIVITY_PAUSED ->
                if (event.packageName == current) null else current
            else -> current
        }

    private fun nextMidnightMs(t: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    private fun dayKey(t: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString()
}
