package com.pcontrol.core

/**
 * Pure queue-awareness decisions for promoting a detection debt into the
 * durable recovery state (TrackerService's `promoteDebt` and its retirement
 * extend branch). Extracted from TrackerService so the invariant below stays
 * unit-testable without Android or Room.
 *
 * INVARIANT: a recovery row's `progressMs` means "recovered through". It must
 * never be raised to a frontier at or above the end of a queued window that
 * has not been recovered yet: the claim step drops a queued window whose end
 * is at or below the row's progress ("fully covered") and erases its journal
 * entry, so a progress jump past a queued window would silently lose that
 * detected outage. When any queued window lies at/below the frontier the
 * install would publish — or overlaps the new span, which loses the window's
 * below-progress prefix the same way — the caller must enqueue the debt
 * window instead, preserving FIFO claim order.
 */
object BackfillPromotion {

    /** What the caller should do with a staged debt. */
    sealed interface Decision {
        /** Durable row install with progressMs = [progressMs], endMs = [endMs]. */
        data class InstallRow(val progressMs: Long, val endMs: Long) : Decision

        /** Enqueue [window] durably instead of installing a row. */
        data class Enqueue(val window: UsageBackfill.Window) : Decision

        /** Nothing to install or queue (debt empty or fully covered). */
        object None : Decision
    }

    /**
     * Debt promotion onto an absent or completed row. [rowEndMs] is the
     * recovered-through frontier the install must not move backwards past
     * (0 when no row exists); [queued] are the pending recovery windows at
     * decision time.
     */
    fun promoteDebt(debt: UsageBackfill.Window, rowEndMs: Long, queued: List<UsageBackfill.Window>): Decision {
        val start = maxOf(debt.startMs, rowEndMs)
        val end = maxOf(debt.endMs, rowEndMs)
        if (end <= start) return Decision.None
        return if (conflictsWithQueue(end, queued)) {
            Decision.Enqueue(UsageBackfill.Window(start, end))
        } else {
            Decision.InstallRow(start, end)
        }
    }

    /**
     * Retirement-time extension: the recovered-through frontier stays at
     * [recoveredEndMs] and only the window's end grows to cover [debt].
     */
    fun extendRow(recoveredEndMs: Long, debt: UsageBackfill.Window, queued: List<UsageBackfill.Window>): Decision {
        val start = maxOf(recoveredEndMs, debt.startMs)
        val end = debt.endMs
        if (end <= start) return Decision.None
        return if (conflictsWithQueue(end, queued)) {
            Decision.Enqueue(UsageBackfill.Window(start, end))
        } else {
            Decision.InstallRow(start, end)
        }
    }

    /**
     * Any queued window starting before [endMs] conflicts with the install:
     * one ending at/below the new progress frontier would be treated as
     * "fully covered" by the claim step and erased; one overlapping the new
     * span would lose its below-progress prefix in the extend branch the
     * same way. Windows starting at/after the frontier are disjoint-above —
     * the queue deliberately leaves them for the post-retirement drain, and
     * a contiguous head (start == endMs) unions into the row exactly once.
     */
    private fun conflictsWithQueue(endMs: Long, queued: List<UsageBackfill.Window>): Boolean =
        queued.any { it.startMs < endMs }
}
