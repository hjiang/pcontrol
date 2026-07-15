package com.pcontrol.app.ui

import com.pcontrol.app.update.UpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Stage 5 tests: pure mapping from every [UpdateResult] to a small
 * [UpdateUiState] describing what the UI should show.
 *
 * Written BEFORE [UpdateUiMapper] exists, so this must fail to compile / fail
 * as expected.
 */
class UpdateUiMapperTest {

    @Test
    fun idleIsIdle() {
        assertEquals(UpdateUiStatus.IDLE, UpdateUiMapper.idle.status)
    }

    @Test
    fun checkingIsChecking() {
        assertEquals(UpdateUiStatus.CHECKING, UpdateUiMapper.checking.status)
    }

    @Test
    fun installTriggeredIsSuccess() {
        assertEquals(UpdateUiStatus.SUCCESS, UpdateUiMapper.fromResult(UpdateResult.INSTALL_TRIGGERED).status)
    }

    @Test
    fun upToDateIsSuccess() {
        assertEquals(UpdateUiStatus.SUCCESS, UpdateUiMapper.fromResult(UpdateResult.UP_TO_DATE).status)
    }

    @Test
    fun signatureMismatchIsActionRequired() {
        assertEquals(
            UpdateUiStatus.ACTION_REQUIRED,
            UpdateUiMapper.fromResult(UpdateResult.SIGNATURE_MISMATCH).status
        )
    }

    @Test
    fun disabledIsActionRequired() {
        assertEquals(UpdateUiStatus.ACTION_REQUIRED, UpdateUiMapper.fromResult(UpdateResult.DISABLED).status)
    }

    @Test
    fun installFailedIsActionRequired() {
        assertEquals(UpdateUiStatus.ACTION_REQUIRED, UpdateUiMapper.fromResult(UpdateResult.INSTALL_FAILED).status)
    }

    @Test
    fun networkErrorIsError() {
        assertEquals(UpdateUiStatus.ERROR, UpdateUiMapper.fromResult(UpdateResult.NETWORK_ERROR).status)
    }

    @Test
    fun downloadFailedIsError() {
        assertEquals(UpdateUiStatus.ERROR, UpdateUiMapper.fromResult(UpdateResult.DOWNLOAD_FAILED).status)
    }

    @Test
    fun versionErrorIsError() {
        assertEquals(UpdateUiStatus.ERROR, UpdateUiMapper.fromResult(UpdateResult.VERSION_ERROR).status)
    }

    @Test
    fun inProgressIsError() {
        assertEquals(UpdateUiStatus.ERROR, UpdateUiMapper.fromResult(UpdateResult.IN_PROGRESS).status)
    }

    @Test
    fun skippedIsError() {
        assertEquals(UpdateUiStatus.ERROR, UpdateUiMapper.fromResult(UpdateResult.SKIPPED).status)
    }

    @Test
    fun everyResultHasAMappedMessageForNonIdle() {
        val terminals = listOf(
            UpdateResult.INSTALL_TRIGGERED,
            UpdateResult.UP_TO_DATE,
            UpdateResult.SIGNATURE_MISMATCH,
            UpdateResult.DISABLED,
            UpdateResult.INSTALL_FAILED,
            UpdateResult.NETWORK_ERROR,
            UpdateResult.DOWNLOAD_FAILED,
            UpdateResult.VERSION_ERROR,
            UpdateResult.IN_PROGRESS,
            UpdateResult.SKIPPED,
        )
        terminals.forEach { r ->
            val s = UpdateUiMapper.fromResult(r)
            assertNotNull("message res for $r must not be null", s.messageResId)
        }
    }

    @Test
    fun idleAndCheckingCarryNoMessage() {
        assertEquals(null, UpdateUiMapper.idle.messageResId)
        assertEquals(null, UpdateUiMapper.checking.messageResId)
    }
}