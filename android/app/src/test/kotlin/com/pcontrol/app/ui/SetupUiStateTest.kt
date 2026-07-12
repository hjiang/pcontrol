package com.pcontrol.app.ui

import com.pcontrol.app.ui.CapabilityId.ACCESSIBILITY
import com.pcontrol.app.ui.CapabilityId.BATTERY
import com.pcontrol.app.ui.CapabilityId.NOTIFICATIONS
import com.pcontrol.app.ui.CapabilityId.SERVER
import com.pcontrol.app.ui.CapabilityId.UPDATER
import com.pcontrol.app.ui.CapabilityId.USAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2 tests: pure presentation state for the setup screen.
 *
 * Written BEFORE the [SetupUiState] builder exists, so it must fail to
 * compile / fail as expected.
 */
class SetupUiStateTest {

    private val allDenied = CapabilityFacts(
        usage = false,
        accessibility = false,
        notifications = false,
        battery = false,
        server = false,
        updater = false,
    )

    private val allGranted = allDenied.copy(
        usage = true,
        accessibility = true,
        notifications = true,
        battery = true,
        server = true,
        updater = true,
    )

    @Test
    fun noneGranted_progressIsZeroOfFive() {
        val state = SetupUiState.build(allDenied)
        assertEquals(5, state.requiredTotal)
        assertEquals(0, state.requiredComplete)
        assertFalse(state.canStart)
    }

    @Test
    fun allRequiredGranted_canStartIsTrue() {
        val state = SetupUiState.build(allGranted)
        assertEquals(5, state.requiredComplete)
        assertEquals(5, state.requiredTotal)
        assertTrue(state.canStart)
    }

    @Test
    fun allRequiredGranted_updaterDeniedStillCanStart() {
        val state = SetupUiState.build(allGranted.copy(updater = false))
        assertTrue("optional updater must not gate monitoring", state.canStart)
        assertEquals(5, state.requiredComplete)
        val updater = state.capabilities.first { it.id == UPDATER }
        assertFalse(updater.granted)
        assertFalse(updater.required)
    }

    @Test
    fun someGranted_countsOnlyGrantedRequiredEntries() {
        val facts = allDenied.copy(usage = true, battery = true, updater = true)
        val state = SetupUiState.build(facts)
        assertEquals(2, state.requiredComplete)
        assertEquals(5, state.requiredTotal)
        assertFalse(state.canStart)
    }

    @Test
    fun capabilityOrderIsStableAndAgreesWithDisplayOrder() {
        val state = SetupUiState.build(allGranted)
        val expectedOrder = listOf(
            USAGE, ACCESSIBILITY, NOTIFICATIONS, BATTERY, SERVER, UPDATER
        )
        assertEquals(expectedOrder, state.capabilities.map { it.id })
    }

    @Test
    fun sectionMembership_unknownIdsRejected() {
        val state = SetupUiState.build(allGranted)
        assertEquals(6, state.capabilities.size)
        val ids = state.capabilities.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
        CapabilityId.entries.forEach { id ->
            assertEquals("exactly one entry for $id", 1, ids.count { it == id })
        }
    }

    @Test
    fun requiredFlagMatchesContract_updaterIsOptionalOthersRequired() {
        val state = SetupUiState.build(allGranted)
        state.capabilities.forEach { cap ->
            if (cap.id == UPDATER) {
                assertFalse("updater must be optional", cap.required)
            } else {
                assertTrue("${cap.id} must be required", cap.required)
            }
        }
    }

    @Test
    fun firstIncompleteRequired_noneGrantedReturnsUsage() {
        val state = SetupUiState.build(allDenied)
        assertEquals(USAGE, state.firstIncompleteRequired?.id)
    }

    @Test
    fun firstIncompleteRequired_someDoneReturnsNextRequired() {
        val facts = allGranted.copy(
            usage = true,
            accessibility = true,
            notifications = false,
        )
        val state = SetupUiState.build(facts)
        assertEquals(NOTIFICATIONS, state.firstIncompleteRequired?.id)
    }

    @Test
    fun firstIncompleteRequired_allRequiredDoneReturnsNull() {
        val facts = allGranted.copy(updater = false)
        val state = SetupUiState.build(facts)
        assertNull(state.firstIncompleteRequired)
    }

    @Test
    fun firstIncompleteRequired_ignoresOptionalUpdater() {
        val facts = allGranted.copy(updater = false)
        val state = SetupUiState.build(facts)
        assertTrue(state.canStart)
        assertNull(state.firstIncompleteRequired)
    }

    @Test
    fun build_isDeterministicForSameFacts() {
        val a = SetupUiState.build(allDenied)
        val b = SetupUiState.build(allDenied)
        assertEquals(a.capabilities.map { it.id }, b.capabilities.map { it.id })
        assertEquals(a.requiredComplete, b.requiredComplete)
        assertEquals(a.canStart, b.canStart)
    }
}