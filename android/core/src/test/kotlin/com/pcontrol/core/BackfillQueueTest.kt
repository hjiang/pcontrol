package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for the recovery-queue arithmetic and the durable
 * disjoint-gap journal codec (issue #79 — TrackerService recovery
 * orchestration coverage). The service's `enqueueClamped` / `journalAppend`
 * / `journalRemove` / `journalLoad` must reproduce exactly these decisions,
 * including the queue-awareness invariant from PR #69's review rounds: a
 * later DISJOINT queued window must never clamp (and silently swallow) a
 * still-unrecovered request (fixed in e8019d6).
 */
class BackfillQueueTest {

    private fun w(start: Long, end: Long) = UsageBackfill.Window(start, end)

    // ── clamp ───────────────────────────────────────────────────────────

    @Test
    fun `clamp leaves the window unchanged when nothing is owed`() {
        val result = BackfillQueue.clamp(window = w(100, 200), owedThroughMs = 100L, queued = emptyList())
        assertEquals(w(100, 200), result)
    }

    @Test
    fun `clamp raises the start to the owed frontier`() {
        val result = BackfillQueue.clamp(window = w(100, 300), owedThroughMs = 250L, queued = emptyList())
        assertEquals(w(250, 300), result)
    }

    @Test
    fun `clamp returns null when the owed frontier reaches the window end`() {
        val result = BackfillQueue.clamp(window = w(100, 300), owedThroughMs = 300L, queued = emptyList())
        assertEquals(null, result)
    }

    @Test
    fun `clamp returns null when the window is fully subsumed`() {
        val result = BackfillQueue.clamp(window = w(100, 300), owedThroughMs = 400L, queued = emptyList())
        assertEquals(null, result)
    }

    @Test
    fun `clamp raises the start past an overlapping queued window`() {
        // THE invariant (e8019d6): the request must not replay [200,300),
        // which is already claimed for recovery by an earlier queued window.
        val result = BackfillQueue.clamp(
            window = w(250, 400),
            owedThroughMs = 100L,
            queued = listOf(w(200, 300))
        )
        assertEquals(w(300, 400), result)
    }

    @Test
    fun `clamp returns null when an overlapping queued window subsumes the request`() {
        val result = BackfillQueue.clamp(
            window = w(250, 400),
            owedThroughMs = 100L,
            queued = listOf(w(200, 450))
        )
        assertEquals(null, result)
    }

    @Test
    fun `clamp ignores a later disjoint queued window`() {
        // REGRESSION PIN (e8019d6): clamping [100,200] against the later
        // disjoint [200,300] would push the request to [300,200] — an empty
        // window — and a real recovery tail would never be replayed.
        // Disjoint queued windows coalesce at claim time instead.
        val result = BackfillQueue.clamp(
            window = w(100, 200),
            owedThroughMs = 100L,
            queued = listOf(w(200, 300))
        )
        assertEquals(w(100, 200), result)
    }

    @Test
    fun `clamp takes the maximum end over overlapping queued windows that cover the frontier`() {
        // A queued window only justifies skipping to its end when it starts
        // at/below the not-yet-claimed frontier (so it covers the request's
        // prefix); a chained pair still resolves to the farthest end.
        val result = BackfillQueue.clamp(
            window = w(100, 500),
            owedThroughMs = 100L,
            queued = listOf(w(100, 200), w(150, 450))
        )
        assertEquals(w(450, 500), result)
    }

    @Test
    fun `clamp preserves the uncovered prefix when a queued window starts inside the span`() {
        // REGRESSION PIN (PR #81 review): queued [300,400] does NOT cover
        // [100,300). The old any-overlap jump set owed to 400, collapsed the
        // request to a zero-width window and returned null — silently
        // dropping the still-unrecovered prefix.
        val result = BackfillQueue.clamp(
            window = w(100, 350),
            owedThroughMs = 100L,
            queued = listOf(w(300, 400))
        )
        assertEquals(w(100, 350), result)
    }

    @Test
    fun `clamp returns null for a zero-width window`() {
        val result = BackfillQueue.clamp(window = w(100, 100), owedThroughMs = 0L, queued = emptyList())
        assertEquals(null, result)
    }

    // ── journal codec ───────────────────────────────────────────────────

    @Test
    fun `entryOf formats start colon end`() {
        assertEquals("100:200", BackfillJournal.entryOf(w(100, 200)))
    }

    @Test
    fun `containsEntry is false for null or absent entries`() {
        assertEquals(false, BackfillJournal.containsEntry(null, w(1, 2)))
        assertEquals(false, BackfillJournal.containsEntry("3:4|5:6", w(1, 2)))
    }

    @Test
    fun `containsEntry matches whole entries only`() {
        // "11:2" must not satisfy a request for "1:2" — the match is on the
        // split('|') element, not a substring.
        assertEquals(false, BackfillJournal.containsEntry("11:2", w(1, 2)))
        assertEquals(true, BackfillJournal.containsEntry("11:2|1:2", w(1, 2)))
    }

    @Test
    fun `containsEntry is false for an empty journal`() {
        // Distinct from the null case: an empty string splits to one blank
        // element, which never equals an entry.
        assertEquals(false, BackfillJournal.containsEntry("", w(1, 2)))
    }

    @Test
    fun `appendEntry joins with the pipe separator`() {
        assertEquals("3:4", BackfillJournal.appendEntry(null, w(3, 4)))
        assertEquals("3:4", BackfillJournal.appendEntry("", w(3, 4)))
        assertEquals("1:2|3:4", BackfillJournal.appendEntry("1:2", w(3, 4)))
    }

    @Test
    fun `decode returns empty for null empty or blank journals`() {
        assertEquals(emptyList<UsageBackfill.Window>(), BackfillJournal.decode(null))
        assertEquals(emptyList<UsageBackfill.Window>(), BackfillJournal.decode(""))
        assertEquals(emptyList<UsageBackfill.Window>(), BackfillJournal.decode("   "))
    }

    @Test
    fun `decode preserves journal order`() {
        assertEquals(listOf(w(1, 2), w(3, 4)), BackfillJournal.decode("1:2|3:4"))
    }

    @Test
    fun `decode drops malformed entries`() {
        // Not numeric, one component, end <= start, too many components.
        assertEquals(
            emptyList<UsageBackfill.Window>(),
            BackfillJournal.decode("abc|5|10:5|1:2:3")
        )
    }

    @Test
    fun `decode drops an entry with a trailing space`() {
        // "6 " fails toLongOrNull, so the entry is malformed — journalRemove
        // must still preserve it verbatim (see removeEntries below).
        assertEquals(emptyList<UsageBackfill.Window>(), BackfillJournal.decode("5:6 "))
    }

    @Test
    fun `decode round-trips entryOf output`() {
        val windows = listOf(w(10, 20), w(30, 40), w(50, 60))
        val raw = windows.joinToString("|") { BackfillJournal.entryOf(it) }
        assertEquals(windows, BackfillJournal.decode(raw))
    }

    @Test
    fun `removeEntries drops only exact matches and keeps order`() {
        val raw = "1:2|3:4|5:6"
        assertEquals("1:2|5:6", BackfillJournal.removeEntries(raw, listOf(w(3, 4))))
    }

    @Test
    fun `removeEntries drops blank segments`() {
        assertEquals("1:2", BackfillJournal.removeEntries("1:2||3:4", listOf(w(3, 4))))
    }

    @Test
    fun `removeEntries with nothing to drop only normalizes`() {
        assertEquals("1:2|3:4", BackfillJournal.removeEntries("1:2|3:4", emptyList()))
    }

    @Test
    fun `removeEntries preserves malformed non-matching entries`() {
        // journalRemove must be string-level: re-encoding through decode()
        // would silently drop a malformed entry the caller never asked to
        // remove.
        assertEquals("abc|5:6", BackfillJournal.removeEntries("1:2|abc|5:6", listOf(w(1, 2))))
    }

    @Test
    fun `removeEntries normalizes a journal with no matching entry`() {
        // Already-normalized input is returned unchanged; blanks still go.
        assertEquals("1:2|3:4", BackfillJournal.removeEntries("1:2|3:4", listOf(w(9, 9))))
    }

    @Test
    fun `removeEntries twice is a no-op`() {
        val raw = "1:2|3:4"
        val once = BackfillJournal.removeEntries(raw, listOf(w(3, 4)))
        assertEquals(once, BackfillJournal.removeEntries(once, listOf(w(3, 4))))
    }
}
