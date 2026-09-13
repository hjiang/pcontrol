package com.pcontrol.core

/**
 * Pure decisions for TrackerService's usage-backfill recovery orchestration
 * (issue #79). Extracted from TrackerService — alongside
 * [BackfillPromotion] and [BackfillQueue] — so the correctness-critical
 * choices below are unit-testable without Android, Room or the service's
 * coroutines. The service owns the state and the locks and must route its
 * decisions through these functions unchanged.
 *
 * Pinned invariants:
 * - A (316d726): the recovery floor is the COMMITTED tick cursor, never the
 *   in-memory event-query anchor;
 * - B: a queued window is claimed only when it belongs with the active row;
 * - C: an owed frontier never jumps past an unrecovered queued window;
 * - E: a completed row is retained until the live cursor catches up and no
 *   extending detection debt remains.
 */
object BackfillRecovery {

    /**
     * INVARIANT A — the recovery floor (the 316d726 correction). Returns
     * [committedCursorMs]: a tick that credited anything always commits the
     * cursor, so the cursor already excludes everything counted, while
     * [queryAnchorMs] — the in-memory event-query anchor — can sit AHEAD of
     * the cursor only when the cursor was held back (no foreground known and
     * UsageStats access unconfirmed: nothing credited). The anchor is
     * accepted here purely as the REJECTED candidate — flooring at it would
     * start a staged recovery after the uncounted stretch and skip that
     * usage permanently — so this decision stays pinned by a test instead of
     * by an argument choice at a call site.
     */
    fun recoveryFloorMs(committedCursorMs: Long, queryAnchorMs: Long?): Long = committedCursorMs

    /**
     * INVARIANT C — the owed span of a merged detection debt after clamping
     * against ranges already claimed for recovery: the active row's end
     * ([rowEndMs], 0 when absent) and every queued window OVERLAPPING the
     * span. The LIVE CURSOR is deliberately excluded (the caller's
     * detection advanced the query anchor past the debt, so live ticks push
     * the cursor forward without sampling the debt's prefix — clamping to it
     * would erase an unpromoted outage). Returns null when the merged span
     * is fully covered: the caller keeps the debt record as-is and lets the
     * claim/retire steps resolve it against the covering state.
     */
    fun owedWindow(
        merged: UsageBackfill.Window,
        rowEndMs: Long,
        queued: List<UsageBackfill.Window>
    ): UsageBackfill.Window? {
        var claimedStart = merged.startMs
        if (rowEndMs > claimedStart) {
            claimedStart = rowEndMs
        }
        for (q in queued) {
            if (q.startMs < merged.endMs && q.endMs > claimedStart) {
                claimedStart = q.endMs
            }
        }
        return if (claimedStart >= merged.endMs) null else UsageBackfill.Window(claimedStart, merged.endMs)
    }

    /** What a worker pass should do with the queue head. */
    sealed interface Claim {
        /** Durable install of a fresh row covering [window] (no active row). */
        data class InstallFreshRow(val window: UsageBackfill.Window) : Claim

        /** Extend the active row's endMs to [endMs] (progress preserved). */
        data class ExtendActiveRow(val endMs: Long) : Claim

        /** The pass could not run (UsageStats access unavailable): touch nothing. */
        object NotHandled : Claim

        /** No claimable head; the active row still owes work (any queued
         *  disjoint head stays queued behind it by definition). */
        object ProcessActiveRow : Claim

        /** Nothing owed by the row: evaluate the cursor-gated retirement.
         *  A DISJOINT head (starting past the row's end) lands here too when
         *  the row is completed: the head is intentionally left queued and
         *  is claimed as a fresh row by the drain loop after the marker
         *  clears — the caller must still run the retirement arm. */
        object EvaluateRetirement : Claim
    }

    /**
     * INVARIANTS B/C — the claim decision of a worker pass, in the service's
     * documented `when` order. [handled] is false when the pass could not
     * run; [rowEndMs]/[rowProgressMs] describe the ACTIVE row (a zeroed row
     * counts as absent, so pass 0/0 for it); [head] is the queue head or
     * null. A head belongs with the active row only when there is no row or
     * it starts at/below the row's end — a DISJOINT head is left queued (or
     * waits behind the row's remaining work): merging it would replay the
     * live-counted stretch between the two outages.
     */
    fun claim(
        handled: Boolean,
        rowEndMs: Long,
        rowProgressMs: Long,
        head: UsageBackfill.Window?
    ): Claim = when {
        !handled -> Claim.NotHandled
        head != null && (rowEndMs <= 0L || head.startMs <= rowEndMs) ->
            if (rowEndMs <= 0L) {
                Claim.InstallFreshRow(head)
            } else {
                Claim.ExtendActiveRow(maxOf(rowEndMs, head.endMs))
            }
        rowEndMs > 0L && rowProgressMs < rowEndMs -> Claim.ProcessActiveRow
        else -> Claim.EvaluateRetirement
    }

    /** Whether the retirement's detection-debt check allows clearing. */
    sealed interface DebtClear {
        /** Clear the debt record (the caller keeps its commit() result). */
        object Clear : DebtClear

        /** INVARIANT E: the live cursor trails the recovered frontier. */
        object KeepUntilCursorCatchesUp : DebtClear

        /** A detection debt extends past the recovered frontier. */
        object KeepForExtendingDebt : DebtClear
    }

    /**
     * INVARIANT E — the retirement gate for the detection debt. The marker
     * row stays the recovered-through record until the live cursor catches
     * up (clearing earlier would let a restart with a stale cursor
     * re-register and replay the window), and a debt extending past the
     * frontier is converted into the next claimed window instead of being
     * wiped. On [DebtClear.Clear] the caller performs the actual durable
     * clear and retries when its commit() fails; every other outcome means
     * retry the retirement after a backoff.
     */
    fun debtClearDecision(frontierMs: Long, recoveredEndMs: Long, debtEndMs: Long?): DebtClear = when {
        frontierMs < recoveredEndMs -> DebtClear.KeepUntilCursorCatchesUp
        debtEndMs != null && debtEndMs > recoveredEndMs -> DebtClear.KeepForExtendingDebt
        else -> DebtClear.Clear
    }

    /**
     * Whether durable recovery work exists and a worker (or the single-
     * flight guard) must stay alive for it: a staged detection debt, any
     * queued window, or a row that still exists (a completed marker owes its
     * cursor-gated retirement too — ANY nonzero row counts).
     */
    fun hasDurableWork(stagedDebt: Boolean, queuedNonEmpty: Boolean, rowEndMs: Long): Boolean =
        stagedDebt || queuedNonEmpty || rowEndMs > 0L
}
