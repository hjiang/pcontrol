package com.pcontrol.app.ui

import androidx.annotation.StringRes
import com.pcontrol.app.R
import com.pcontrol.app.update.UpdateResult

/**
 * Stage 5 — durable, accessible update feedback state.
 *
 * A small presentation model describing what the Updates card should show
 * after an update check. The mapper ([fromResult]) is pure; rendering and the
 * accessibility live-region announcement happen in [com.pcontrol.app.MainActivity].
 *
 * Status categories (Section 5):
 *  - [IDLE]: nothing to show (initial / cleared).
 *  - [CHECKING]: a check is running; the UI should show a progress indicator.
 *  - [SUCCESS]: the operation succeeded (up-to-date or install triggered).
 *  - [ACTION_REQUIRED]: the user must do something (re-enable auto-update,
 *    allow installs, accept a manual update because of a signature mismatch,
 *    or retry an install that could not open the dialog).
 *  - [ERROR]: a recoverable error occurred (network, download, version,
 *    in-progress, or "check again later"); the message stays visible beside
 *    the controls until replaced by another result (never only in a Toast).
 */
enum class UpdateUiStatus { IDLE, CHECKING, SUCCESS, ACTION_REQUIRED, ERROR }

data class UpdateUiState(
    val status: UpdateUiStatus,
    @StringRes val messageResId: Int? = null,
)

/**
 * Pure mapping from [UpdateResult] → [UpdateUiState].
 *
 * Production constructs the existing [com.pcontrol.app.update.UpdateCoordinator]
 * and uses its real IO behavior; this object only translates the result enum.
 */
object UpdateUiMapper {
    val idle = UpdateUiState(UpdateUiStatus.IDLE)
    val checking = UpdateUiState(UpdateUiStatus.CHECKING)

    fun fromResult(result: UpdateResult): UpdateUiState = when (result) {
        UpdateResult.INSTALL_TRIGGERED ->
            UpdateUiState(UpdateUiStatus.SUCCESS, R.string.update_result_install_triggered)
        UpdateResult.UP_TO_DATE ->
            UpdateUiState(UpdateUiStatus.SUCCESS, R.string.update_result_up_to_date)

        UpdateResult.SIGNATURE_MISMATCH ->
            UpdateUiState(UpdateUiStatus.ACTION_REQUIRED, R.string.update_result_signature_mismatch)
        UpdateResult.DISABLED ->
            UpdateUiState(UpdateUiStatus.ACTION_REQUIRED, R.string.update_result_disabled)
        UpdateResult.INSTALL_FAILED ->
            UpdateUiState(UpdateUiStatus.ACTION_REQUIRED, R.string.update_result_install_failed)

        UpdateResult.NETWORK_ERROR ->
            UpdateUiState(UpdateUiStatus.ERROR, R.string.update_result_network_error)
        UpdateResult.DOWNLOAD_FAILED ->
            UpdateUiState(UpdateUiStatus.ERROR, R.string.update_result_download_failed)
        UpdateResult.VERSION_ERROR ->
            UpdateUiState(UpdateUiStatus.ERROR, R.string.update_result_version_error)
        UpdateResult.IN_PROGRESS ->
            UpdateUiState(UpdateUiStatus.ERROR, R.string.update_result_in_progress)
        UpdateResult.SKIPPED ->
            UpdateUiState(UpdateUiStatus.ERROR, R.string.update_result_skipped)
    }
}