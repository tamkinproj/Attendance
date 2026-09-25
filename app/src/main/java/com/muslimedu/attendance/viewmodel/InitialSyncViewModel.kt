package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.StudentDownloadRepository
import com.muslimedu.attendance.data.repository.StudentDownloadUnavailableException
import com.muslimedu.attendance.sync.GateSyncManager
import com.muslimedu.attendance.sync.GateSyncOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [Skipped]: nothing went wrong on the device - the server just doesn't offer that step yet. Doesn't block the gate. */
enum class SyncStepStatus { Waiting, Running, Done, Skipped, Failed }

data class SyncStepState(val status: SyncStepStatus = SyncStepStatus.Waiting, val detail: String? = null)

data class InitialSyncUiState(
    val upload: SyncStepState = SyncStepState(),
    val download: SyncStepState = SyncStepState(),
) {
    val isRunning: Boolean get() = upload.status == SyncStepStatus.Running || download.status == SyncStepStatus.Running
    val allDone: Boolean get() = upload.status.isComplete && download.status.isComplete
    val hasFailure: Boolean get() = upload.status == SyncStepStatus.Failed || download.status == SyncStepStatus.Failed
}

private val SyncStepStatus.isComplete: Boolean
    get() = this == SyncStepStatus.Done || this == SyncStepStatus.Skipped

/**
 * The step between a fresh admin sign-in and the gate: upload anything
 * scanned on this device, then download the school's student list.
 *
 * Upload runs first so nothing already recorded is at risk. Either step can
 * fail (offline, or `admin_gate_students` not live on the backend yet) - the
 * admin can retry, or continue and sync later from Admin > Sync & Account,
 * since blocking the gate on a server problem would stop students entering.
 */
@HiltViewModel
class InitialSyncViewModel @Inject constructor(
    private val gateSyncManager: GateSyncManager,
    private val gateAttendanceRepository: GateAttendanceRepository,
    private val studentDownloadRepository: StudentDownloadRepository,
    private val deviceSettings: DeviceSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(InitialSyncUiState())
    val state: StateFlow<InitialSyncUiState> = _state.asStateFlow()

    /**
     * Called each time the sync screen appears (not from init): this
     * ViewModel outlives the screen, so a second sign-in must re-run it
     * rather than show the previous run's result.
     */
    fun start() {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            _state.value = InitialSyncUiState(upload = SyncStepState(SyncStepStatus.Running))
            _state.value = _state.value.copy(
                upload = upload(),
                download = SyncStepState(SyncStepStatus.Running),
            )
            _state.value = _state.value.copy(download = download())
        }
    }

    /** Ends the sync step - whether it all succeeded or the admin chose to continue anyway. */
    fun finish() {
        deviceSettings.setPostLoginSyncPending(false)
    }

    /** Always runs, even with no attendance waiting - card registrations made on this device go up here too. */
    private suspend fun upload(): SyncStepState {
        val waiting = gateAttendanceRepository.unsyncedAttendanceCount()
        return when (val outcome = gateSyncManager.flush()) {
            GateSyncOutcome.NotSignedIn -> SyncStepState(SyncStepStatus.Failed, "Not signed in")
            is GateSyncOutcome.Finished -> {
                val summary = buildString {
                    append(if (waiting == 0) "No attendance waiting" else "${outcome.uploaded} of $waiting uploaded")
                    if (outcome.rejected > 0) append(", ${outcome.rejected} rejected")
                    if (outcome.cardsSynced > 0) append(", ${outcome.cardsSynced} card(s) registered")
                    if (outcome.cardsFailed > 0) append(", ${outcome.cardsFailed} card(s) refused - see Admin > Students")
                }
                when {
                    outcome.stoppedReason == null -> SyncStepState(SyncStepStatus.Done, summary)
                    // Not the device's fault and not fixable from here -
                    // same "not set up yet" treatment as the student download.
                    outcome.endpointMissing -> SyncStepState(SyncStepStatus.Skipped, "$summary - ${outcome.stoppedReason}")
                    else -> SyncStepState(SyncStepStatus.Failed, "$summary - ${outcome.stoppedReason}")
                }
            }
        }
    }

    private suspend fun download(): SyncStepState = studentDownloadRepository.download().fold(
        onSuccess = { s ->
            val total = s.added + s.updated
            SyncStepState(SyncStepStatus.Done, "$total students (${s.added} new, ${s.updated} updated)")
        },
        onFailure = {
            val status = if (it is StudentDownloadUnavailableException) SyncStepStatus.Skipped else SyncStepStatus.Failed
            SyncStepState(status, it.message ?: "Download failed")
        },
    )
}
