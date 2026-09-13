package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for the TrackerService recovery-orchestration decisions
 * (issue #79). Each test pins one invariant the PR #69 review rounds either
 * fixed (e8019d6 queued-window swallow) or corrected at the call site
 * without a possible test (316d726 recovery floor).
 *
 * Deliberately NOT covered here: the single-flight guard handoff
 * (`backfillInFlight` acquire/release vs. detector publication, the other
 * e8019d6 fix). `hasDurableWork` is only that decision's liveness input — a
 * Boolean cannot exercise the `backfillMutex` serialization, so invariant D
 * stays manual-only (see docs/plans/16_tracker_recovery_test_coverage.md),
 * exactly as the review of this suite pointed out.
 *
 * - A: the recovery floor is the COMMITTED cursor, never the in-memory
 *   query anchor;
 * - B: claim ordering — a queued window is only claimed when it belongs
 *   with the active row, otherwise it waits (or the row keeps recovering);
 * - C: queue awareness — an owed frontier never jumps past an unrecovered
 *   queued window;
 * - E: completed-marker retention — retirement waits until the live cursor
 *   catches up and no extending debt remains.
 */
class BackfillRecoveryTest {

    private fun w(start: Long, end: Long) = UsageBackfill.Window(start, end)

    // ── recoveryFloorMs (invariant A, the 316d726 correction) ───────────

    @Test
    fun `floor is the committed cursor even when the anchor is ahead`() {
        // THE regression: commitTick advances the anchor while deliberately
        // holding the cursor back (nothing credited), so flooring at the
        // anchor would skip exactly the uncounted stretch. Fixed in
        // 316d726; previously untestable at any seam.
        assertEquals(500L, BackfillRecovery.recoveryFloorMs(committedCursorMs = 500L, queryAnchorMs = 900L))
    }

    @Test
    fun `floor is the committed cursor when the anchor is behind`() {
        assertEquals(500L, BackfillRecovery.recoveryFloorMs(committedCursorMs = 500L, queryAnchorMs = 100L))
    }

    @Test
    fun `floor is the committed cursor with no anchor`() {
        assertEquals(500L, BackfillRecovery.recoveryFloorMs(committedCursorMs = 500L, queryAnchorMs = null))
    }

    @Test
    fun `floor passes a zero cursor through`() {
        // Fresh install: no cursor, no floor — UsageBackfill.plan decides.
        assertEquals(0L, BackfillRecovery.recoveryFloorMs(committedCursorMs = 0L, queryAnchorMs = 999L))
    }

    // ── owedWindow (invariant C) ────────────────────────────────────────

    @Test
    fun `owed window starts at the row end when the row covers the prefix`() {
        val result = BackfillRecovery.owedWindow(merged = w(100, 500), rowEndMs = 200L, queued = emptyList())
        assertEquals(w(200, 500), result)
    }

    @Test
    fun `owed window is raised past a queued window that covers the frontier`() {
        val result = BackfillRecovery.owedWindow(merged = w(100, 500), rowEndMs = 0L, queued = listOf(w(100, 300)))
        assertEquals(w(300, 500), result)
    }

    @Test
    fun `owed window preserves the uncovered prefix when a queued window starts inside the span`() {
        // REGRESSION PIN (PR #81 review): queued [300,400] does not cover
        // [100,300), so the merged span must not collapse to that window's
        // end. The old any-overlap jump returned null and dropped an
        // unpromoted outage.
        val result = BackfillRecovery.owedWindow(
            merged = w(100, 350),
            rowEndMs = 0L,
            queued = listOf(w(300, 400))
        )
        assertEquals(w(100, 350), result)
    }

    @Test
    fun `owed window ignores a disjoint queued window above the span`() {
        val result = BackfillRecovery.owedWindow(merged = w(100, 200), rowEndMs = 0L, queued = listOf(w(300, 400)))
        assertEquals(w(100, 200), result)
    }

    @Test
    fun `owed window is null when the row end reaches the merged end`() {
        assertEquals(null, BackfillRecovery.owedWindow(merged = w(100, 200), rowEndMs = 200L, queued = emptyList()))
        assertEquals(null, BackfillRecovery.owedWindow(merged = w(100, 200), rowEndMs = 300L, queued = emptyList()))
    }

    @Test
    fun `owed window is null when a queued window covers everything`() {
        val result = BackfillRecovery.owedWindow(merged = w(100, 200), rowEndMs = 0L, queued = listOf(w(50, 300)))
        assertEquals(null, result)
    }

    @Test
    fun `owed window takes the maximum claimed end over queued windows covering the frontier`() {
        val result = BackfillRecovery.owedWindow(
            merged = w(100, 500),
            rowEndMs = 0L,
            queued = listOf(w(100, 200), w(150, 450))
        )
        assertEquals(w(450, 500), result)
    }

    @Test
    fun `owed window is unchanged with no row and no queue`() {
        val result = BackfillRecovery.owedWindow(merged = w(100, 500), rowEndMs = 0L, queued = emptyList())
        assertEquals(w(100, 500), result)
    }

    // ── claim (invariants B/C — the runRecovery `when` order) ──────────

    @Test
    fun `an unhandled pass claims nothing even with a claimable head`() {
        // UsageStats access unavailable: the active row AND the queue stay
        // exactly as they are — claiming would overwrite the row's
        // unrecovered remainder.
        assertEquals(
            BackfillRecovery.Claim.NotHandled,
            BackfillRecovery.claim(handled = false, rowEndMs = 0L, rowProgressMs = 0L, head = w(100, 200))
        )
    }

    @Test
    fun `a claimable head with no row installs a fresh row`() {
        assertEquals(
            BackfillRecovery.Claim.InstallFreshRow(w(100, 200)),
            BackfillRecovery.claim(handled = true, rowEndMs = 0L, rowProgressMs = 0L, head = w(100, 200))
        )
    }

    @Test
    fun `a zeroed row counts as absent at claim time`() {
        // dao.clear() keeps the singleton row zeroed; treating it as active
        // would make every queued head unclaimable forever (livelock).
        assertEquals(
            BackfillRecovery.Claim.InstallFreshRow(w(100, 200)),
            BackfillRecovery.claim(handled = true, rowEndMs = 0L, rowProgressMs = 0L, head = w(100, 200))
        )
    }

    @Test
    fun `a contiguous head extends the active row exactly once`() {
        assertEquals(
            BackfillRecovery.Claim.ExtendActiveRow(400L),
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 200L, head = w(200, 400))
        )
    }

    @Test
    fun `an overlapping head extends the active row`() {
        assertEquals(
            BackfillRecovery.Claim.ExtendActiveRow(300L),
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 100L, head = w(150, 300))
        )
    }

    @Test
    fun `a head fully below progress still extends (to the row end)`() {
        // Covered entries are removed from the queue by the caller; the
        // row's end must not move.
        assertEquals(
            BackfillRecovery.Claim.ExtendActiveRow(200L),
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 200L, head = w(100, 150))
        )
    }

    @Test
    fun `a disjoint head with a completed row evaluates retirement`() {
        // Merging it into the row would replay the live-counted stretch
        // between the two outages. The head is left queued ON PURPOSE: the
        // retirement clears the marker and the drain loop then claims the
        // head as a fresh row with its own progress frontier. Returning a
        // "leave queued" outcome here instead would skip the retirement and
        // strand the completed marker forever.
        assertEquals(
            BackfillRecovery.Claim.EvaluateRetirement,
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 200L, head = w(300, 400))
        )
    }

    @Test
    fun `a disjoint head waits while the row still owes work`() {
        // The row-owes arm comes after the queue arm: the head is left
        // queued only implicitly — the pass processes the row first.
        assertEquals(
            BackfillRecovery.Claim.ProcessActiveRow,
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 150L, head = w(300, 400))
        )
    }

    @Test
    fun `no head with a mid-recovery row processes the row`() {
        assertEquals(
            BackfillRecovery.Claim.ProcessActiveRow,
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 100L, head = null)
        )
    }

    @Test
    fun `no head with a completed row evaluates retirement`() {
        assertEquals(
            BackfillRecovery.Claim.EvaluateRetirement,
            BackfillRecovery.claim(handled = true, rowEndMs = 200L, rowProgressMs = 200L, head = null)
        )
    }

    @Test
    fun `no head and no row evaluates retirement`() {
        assertEquals(
            BackfillRecovery.Claim.EvaluateRetirement,
            BackfillRecovery.claim(handled = true, rowEndMs = 0L, rowProgressMs = 0L, head = null)
        )
    }

    // ── debtClearDecision (invariant E) ─────────────────────────────────

    @Test
    fun `retirement waits while the cursor trails the recovered frontier`() {
        // THE completed-marker invariant: clearing earlier would let a
        // restart with a stale cursor re-register and replay the window.
        assertEquals(
            BackfillRecovery.DebtClear.KeepUntilCursorCatchesUp,
            BackfillRecovery.debtClearDecision(frontierMs = 100L, recoveredEndMs = 200L, debtEndMs = null)
        )
    }

    @Test
    fun `retirement waits for an extending detection debt`() {
        assertEquals(
            BackfillRecovery.DebtClear.KeepForExtendingDebt,
            BackfillRecovery.debtClearDecision(frontierMs = 200L, recoveredEndMs = 200L, debtEndMs = 300L)
        )
    }

    @Test
    fun `retirement clears at the frontier with no debt`() {
        assertEquals(
            BackfillRecovery.DebtClear.Clear,
            BackfillRecovery.debtClearDecision(frontierMs = 200L, recoveredEndMs = 200L, debtEndMs = null)
        )
    }

    @Test
    fun `a debt ending at the recovered frontier is covered and clearable`() {
        assertEquals(
            BackfillRecovery.DebtClear.Clear,
            BackfillRecovery.debtClearDecision(frontierMs = 200L, recoveredEndMs = 200L, debtEndMs = 200L)
        )
    }

    @Test
    fun `a cursor past the recovered frontier with no debt clears`() {
        assertEquals(
            BackfillRecovery.DebtClear.Clear,
            BackfillRecovery.debtClearDecision(frontierMs = 300L, recoveredEndMs = 200L, debtEndMs = null)
        )
    }

    // ── hasDurableWork (guard-liveness input) ───────────────────────────

    @Test
    fun `no durable work when everything is clear`() {
        assertEquals(false, BackfillRecovery.hasDurableWork(stagedDebt = false, queuedNonEmpty = false, rowEndMs = 0L))
    }

    @Test
    fun `each durable record alone counts as work`() {
        assertEquals(true, BackfillRecovery.hasDurableWork(stagedDebt = true, queuedNonEmpty = false, rowEndMs = 0L))
        assertEquals(true, BackfillRecovery.hasDurableWork(stagedDebt = false, queuedNonEmpty = true, rowEndMs = 0L))
        assertEquals(true, BackfillRecovery.hasDurableWork(stagedDebt = false, queuedNonEmpty = false, rowEndMs = 5L))
    }
}
