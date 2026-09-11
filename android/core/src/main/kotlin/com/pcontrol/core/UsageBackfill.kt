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

    /**
     * One chunk of a chunked attribution: a contiguous sub-window of the
     * overall window plus the slices attributed within it. Chunks let a
     * caller merge counters and persist durable progress piece by piece
     * (TrackerService), so a mid-recovery process death can never put more
     * than one chunk's data at risk.
     */
    data class Chunk(val window: Window, val slices: List<Slice>)

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
    ): List<Slice> =
        attributeChunked(
            events = events,
            selfPackage = selfPackage,
            window = window,
            chunkMs = maxOf(1L, window.endMs - window.startMs),
            zone = zone,
            silenceCapMs = silenceCapMs
        ).flatMap { it.slices }

    /**
     * [attribute] split into consecutive chunks of at most [chunkMs] for
     * durable per-chunk progress. Per-interval semantics are identical to
     * the single-shot call — in particular the silence cap is measured
     * from the true interval start, NOT restarted at every chunk boundary
     * (a 45-minute eventless stretch contributes at most [silenceCapMs]
     * in total, never [silenceCapMs] per chunk it spans).
     *
     * [countFromMs] keeps that guarantee across RETRIES: a caller that
     * resumes an interrupted recovery from a durable progress frontier
     * passes the frontier here so time before it (already merged by the
     * committed chunks) is not counted again, while interval starts and
     * caps are still measured on the true event timeline — an interval
     * whose allowance was consumed before the frontier gets nothing back.
     * Sub-second remainders are carried across chunks, so the per-chunk
     * second totals sum exactly to the single-shot conversion — within one
     * invocation: a retry from a durable frontier re-floors the sub-second
     * remainder at that frontier, bounded by < 1 s per (day, subject).
     */
    fun attributeChunked(
        events: List<TimedAppEvent>,
        selfPackage: String,
        window: Window,
        chunkMs: Long,
        zone: ZoneId,
        silenceCapMs: Long = DEFAULT_SILENCE_CAP_MS,
        countFromMs: Long = window.startMs
    ): List<Chunk> {
        require(chunkMs > 0) { "chunkMs must be positive" }
        val chunkEnds = ArrayList<Long>()
        var boundary = window.startMs
        while (boundary < window.endMs) {
            chunkEnds += minOf(boundary + chunkMs, window.endMs)
            boundary += chunkMs
        }
        if (chunkEnds.isEmpty()) return emptyList()
        val accs = List(chunkEnds.size) { LinkedHashMap<Pair<String, String>, Long>() }

        // Charges [fromMs, toMs) to [pkg], distributing the (already
        // silence-capped) span over the chunks it overlaps. Only time at or
        // after [countFromMs] is counted; the cap clock still runs from the
        // true interval start.
        fun charge(fromMs: Long, toMs: Long, pkg: String?) {
            if (pkg == null || pkg == selfPackage || toMs <= fromMs) return
            val cappedEnd = minOf(toMs, fromMs + silenceCapMs)
            val countedFrom = maxOf(fromMs, countFromMs)
            if (countedFrom >= cappedEnd) return
            var chunkStart = window.startMs
            for (i in chunkEnds.indices) {
                val chunkEnd = chunkEnds[i]
                val from = maxOf(countedFrom, chunkStart)
                val to = minOf(cappedEnd, chunkEnd)
                if (to > from) addInterval(accs[i], pkg, from, to, zone)
                chunkStart = chunkEnd
            }
        }

        var foreground: String? = null
        var intervalStart = window.startMs
        for (event in events.sortedBy { it.timestampMs }) {
            if (event.eventType !in TRANSITION_TYPES) continue
            val ts = event.timestampMs.coerceIn(window.startMs, window.endMs)
            val next = applyTransition(foreground, event)
            if (next != foreground) {
                // Only an actual change of the attribution state closes the
                // interval: a PAUSED for a non-current app (stray or
                // out-of-order event) is a no-op and must not restart the
                // silence-cap clock of the app that is still foreground.
                if (ts > intervalStart) charge(intervalStart, ts, foreground)
                intervalStart = ts
                foreground = next
            }
        }
        if (window.endMs > intervalStart) {
            charge(intervalStart, window.endMs, foreground)
        }

        // Carry sub-second remainders into the next chunk so the per-chunk
        // Slice seconds sum exactly to the single-shot conversion — flooring
        // every chunk independently would drop up to 1 s per key at each
        // chunk boundary an interval crosses.
        val carry = HashMap<Pair<String, String>, Long>()
        return chunkEnds.mapIndexed { i, end ->
            val start = if (i == 0) window.startMs else chunkEnds[i - 1]
            Chunk(
                window = Window(start, end),
                slices = accs[i].map { (dayAndSubject, ms) ->
                    val totalMs = ms + (carry[dayAndSubject] ?: 0L)
                    carry[dayAndSubject] = totalMs % 1000L
                    Slice(dayAndSubject.first, dayAndSubject.second, (totalMs / 1000L).toInt())
                }
            )
        }
    }

    /**
     * Charges [fromMs, toMs) to [pkg], splitting at local midnights so day
     * keys match the live tick path's device-local convention. The caller
     * applies any silence cap; this only distributes a final span across
     * day buckets.
     */
    private fun addInterval(
        acc: MutableMap<Pair<String, String>, Long>,
        pkg: String,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId
    ) {
        if (toMs <= fromMs) return
        var t = fromMs
        while (t < toMs) {
            val segmentEnd = minOf(toMs, nextMidnightMs(t, zone))
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
