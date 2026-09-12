package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Review-round-1 regression tests for the queue-awareness invariant: a
 * recovery row's progressMs must never be raised past an unrecovered queued
 * window (the claim step would treat it as "fully covered" and erase it —
 * silent loss of a detected outage). See BackfillPromotion for the contract.
 */
class BackfillPromotionTest {

    private fun w(start: Long, end: Long) = UsageBackfill.Window(start, end)

    // ── promoteDebt ─────────────────────────────────────────────────────

    @Test
    fun `queued window ending below the promoted frontier forces enqueue`() {
        // THE regression: queued B=[150,200] is unrecovered; installing the
        // debt C as a row with progress=300 jumps past it, and the claim
        // step then erases B as "fully covered". Must enqueue instead.
        val decision = BackfillPromotion.promoteDebt(
            debt = w(300, 500),
            rowEndMs = 100L,
            queued = listOf(w(150, 200))
        )
        assertEquals(BackfillPromotion.Decision.Enqueue(w(300, 500)), decision)
    }

    @Test
    fun `queued window overlapping the new span forces enqueue`() {
        // The claim step's extend branch preserves progressMs, so a queued
        // window starting below the new progress loses its below-progress
        // prefix — route it through the FIFO queue instead.
        val decision = BackfillPromotion.promoteDebt(
            debt = w(300, 500),
            rowEndMs = 100L,
            queued = listOf(w(200, 400))
        )
        assertEquals(BackfillPromotion.Decision.Enqueue(w(300, 500)), decision)
    }

    @Test
    fun `disjoint queued window above the span installs the row`() {
        // The disjoint head waits for retirement by design; the row's span
        // is untouched by it.
        val decision = BackfillPromotion.promoteDebt(
            debt = w(300, 400),
            rowEndMs = 100L,
            queued = listOf(w(500, 600))
        )
        assertEquals(BackfillPromotion.Decision.InstallRow(300, 400), decision)
    }

    @Test
    fun `empty queue installs the row clamped to the row end`() {
        val decision = BackfillPromotion.promoteDebt(
            debt = w(150, 500),
            rowEndMs = 300L,
            queued = emptyList()
        )
        assertEquals(BackfillPromotion.Decision.InstallRow(300, 500), decision)
    }

    @Test
    fun `debt fully covered by the row end is none`() {
        val decision = BackfillPromotion.promoteDebt(
            debt = w(150, 180),
            rowEndMs = 300L,
            queued = emptyList()
        )
        assertEquals(BackfillPromotion.Decision.None, decision)
    }

    @Test
    fun `contiguous queued window does not force enqueue`() {
        // [500,600] starting exactly at the row's end is claim-time
        // contiguous (the extend branch unions it exactly once) — no loss.
        val decision = BackfillPromotion.promoteDebt(
            debt = w(300, 500),
            rowEndMs = 100L,
            queued = listOf(w(500, 600))
        )
        assertEquals(BackfillPromotion.Decision.InstallRow(300, 500), decision)
    }

    // ── extendRow ───────────────────────────────────────────────────────

    @Test
    fun `extension past a queued window forces enqueue`() {
        // Retirement extend with progressMs = max(recoveredEnd, debt.start)
        // would jump over the unrecovered [150,200] the same way.
        val decision = BackfillPromotion.extendRow(
            recoveredEndMs = 100L,
            debt = w(300, 500),
            queued = listOf(w(150, 200))
        )
        assertEquals(BackfillPromotion.Decision.Enqueue(w(300, 500)), decision)
    }

    @Test
    fun `clean extension installs at the later of frontier and debt start`() {
        val decision = BackfillPromotion.extendRow(
            recoveredEndMs = 100L,
            debt = w(300, 500),
            queued = emptyList()
        )
        assertEquals(BackfillPromotion.Decision.InstallRow(300, 500), decision)
    }

    @Test
    fun `extension overlapping the recovered frontier starts at the frontier`() {
        // A disjoint outage detected while recovery ran: the stretch up to
        // recoveredEnd was live-counted, so the window starts there.
        val decision = BackfillPromotion.extendRow(
            recoveredEndMs = 200L,
            debt = w(150, 500),
            queued = emptyList()
        )
        assertEquals(BackfillPromotion.Decision.InstallRow(200, 500), decision)
    }
}
