package com.pcontrol.core

/**
 * Queue arithmetic + journal codec for TrackerService's disjoint-gap
 * recovery queue (issue #79). Extracted from TrackerService so the decisions
 * below stay unit-testable without Android or Room; the service owns the
 * state (the in-memory deque, the prefs journal string, the locks and the
 * apply()/commit() split) and must reproduce exactly these results.
 *
 * INVARIANT (PR #69, fixed in e8019d6): a queued request is clamped against
 * ranges already claimed for recovery — but a later DISJOINT queued window
 * deliberately does NOT clamp it. Clamping against a later disjoint window
 * would push the request past its own end and silently drop a real recovery
 * tail; disjoint ranges coalesce at claim time instead.
 */
object BackfillQueue {

    /**
     * Clamps [window] against [owedThroughMs] and every QUEUED window that
     * COVERS the frontier it would advance past (their ends are claimed-for
     * recovery too — two detections with a stale frontier would otherwise
     * enqueue overlapping ranges). Returns null when nothing remains to
     * queue (null input is the caller's concern): fully subsumed requests
     * are dropped, and a zero-width result is dropped the same way.
     *
     * Only a queued window starting AT OR BELOW the not-yet-claimed frontier
     * (`maxOf(owedThroughMs, window.startMs)`) justifies skipping to its end:
     * such a window provably covers the request's prefix. A queued window
     * starting strictly inside the span (a non-contiguous queue) leaves the
     * uncovered prefix IN the result instead of silently dropping it — a
     * PR #81 review finding: the previous any-overlap jump to `q.endMs`
     * treated `[100,350]` against queued `[300,400]` as fully subsumed and
     * discarded the still-unrecovered `[100,300)`.
     *
     * The sweep runs over the queued windows sorted by start, so chained
     * coverage resolves to the farthest reachable end regardless of the
     * queue's (explicitly non-chronological) order — a window extending the
     * frontier can legally arrive before the window that reaches it
     * (PR #81 round 3: `[150,450]` before `[100,200]` must still clamp
     * `[100,500]` to `[450,500]`, not `[200,500]`).
     */
    fun clamp(
        window: UsageBackfill.Window,
        owedThroughMs: Long,
        queued: List<UsageBackfill.Window>
    ): UsageBackfill.Window? {
        var owed = owedThroughMs
        // Sorted-start sweep: once a window starts past the frontier, every
        // later start does too, so the frontier is final and the result is
        // order-independent.
        for (q in queued.sortedBy { it.startMs }) {
            val frontier = maxOf(owed, window.startMs)
            if (q.startMs > frontier) break
            if (q.endMs > frontier) owed = q.endMs
        }
        val clamped = if (owed > window.startMs) {
            UsageBackfill.Window(owed, maxOf(window.endMs, owed))
        } else {
            window
        }
        return if (clamped.endMs <= clamped.startMs) null else clamped
    }
}

/**
 * String codec for the prefs journal that durably backs the in-memory
 * recovery queue: `"startMs:endMs"` entries joined by `'|'`. Deliberately
 * STRING-level, not window-level: [removeEntries] must preserve entries it
 * was not asked to remove — including malformed ones left by an older
 * build — instead of silently re-encoding the journal through [decode].
 */
object BackfillJournal {

    /** The journal entry for [window]: `"startMs:endMs"`. */
    fun entryOf(window: UsageBackfill.Window): String = "${window.startMs}:${window.endMs}"

    /**
     * Whether [raw] already contains [window]'s entry — WHOLE-element match
     * on the `'|'`-split parts, never a substring match ("11:2" must not
     * satisfy "1:2"). Null counts as empty.
     */
    fun containsEntry(raw: String?, window: UsageBackfill.Window): Boolean =
        raw != null && raw.split('|').any { it == entryOf(window) }

    /**
     * [raw] with [window]'s entry appended (pure concatenation; dedupe and
     * durability stay at the call site). Null/empty input starts the journal.
     */
    fun appendEntry(raw: String?, window: UsageBackfill.Window): String =
        if (raw.isNullOrEmpty()) entryOf(window) else "$raw|${entryOf(window)}"

    /**
     * Parses a journal into its well-formed windows (oldest first): entries
     * that are not `start:end` with `end > start` are dropped. Used for
     * startup re-ingestion only — removal goes through [removeEntries].
     */
    fun decode(raw: String?): List<UsageBackfill.Window> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('|').mapNotNull { part ->
            val parts = part.split(':')
            if (parts.size == 2) {
                val s = parts[0].toLongOrNull()
                val e = parts[1].toLongOrNull()
                if (s != null && e != null && e > s) {
                    return@mapNotNull UsageBackfill.Window(s, e)
                }
            }
            null
        }
    }

    /**
     * [raw] without [windows]' entries, order preserved. Blank segments are
     * normalized away; non-matching entries — malformed ones included — are
     * kept verbatim, so a removal can never discard a range the caller did
     * not name. (The null-journal short-circuit stays at the call site.)
     */
    fun removeEntries(raw: String?, windows: Collection<UsageBackfill.Window>): String {
        val drop = windows.map { entryOf(it) }.toSet()
        return raw.orEmpty().split('|')
            .filter { it.isNotBlank() && it !in drop }
            .joinToString("|")
    }
}
