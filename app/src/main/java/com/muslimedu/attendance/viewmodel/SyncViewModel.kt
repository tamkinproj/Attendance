package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.StudentDownloadRepository
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.sync.GateSyncManager
import com.muslimedu.attendance.sync.GateSyncOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
    private val gateSyncManager: GateSyncManager,
    private val studentDownloadRepository: StudentDownloadRepository,
    private val studentRepository: StudentRepository,
    private val deviceSettings: DeviceSettings,
) : ViewModel() {

    /** Face-confirmed attendance not yet on the server. */
    val pendingCount: StateFlow<Int> = gateAttendanceRepository.observeUnsyncedAttendanceCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val syncedCount: StateFlow<Int> = gateAttendanceRepository.observeCount(GateScanEntity.SYNC_SYNCED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val failedScans: StateFlow<List<GateScanEntity>> = gateAttendanceRepository.observeFailed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isUploading: StateFlow<Boolean> = gateSyncManager.isSyncing
    val schoolId: StateFlow<Int> = deviceSettings.schoolId

    private val _uploadMessage = MutableStateFlow<String?>(null)
    val uploadMessage: StateFlow<String?> = _uploadMessage.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadMessage = MutableStateFlow<String?>(null)
    val downloadMessage: StateFlow<String?> = _downloadMessage.asStateFlow()

    private val _studentCount = MutableStateFlow(0)
    val studentCount: StateFlow<Int> = _studentCount.asStateFlow()

    val lastStudentDownloadAt: Long? get() = deviceSettings.lastStudentDownloadAt

    fun refresh() {
        viewModelScope.launch { _studentCount.value = studentRepository.getAll().size }
    }

    fun uploadNow() {
        viewModelScope.launch {
            _uploadMessage.value = when (val outcome = gateSyncManager.flush()) {
                GateSyncOutcome.NotSignedIn -> "Sign in as a school admin to upload."
                is GateSyncOutcome.Finished -> buildString {
                    append("Uploaded ${outcome.uploaded}")
                    if (outcome.rejected > 0) append(", ${outcome.rejected} rejected by server")
                    outcome.stoppedReason?.let { append(". Stopped: $it") }
                    if (outcome.cardsSynced > 0) append(". ${outcome.cardsSynced} card(s) registered")
                    if (outcome.cardsFailed > 0) append(". ${outcome.cardsFailed} card(s) refused - see Students")
                    outcome.cardsStoppedReason?.let { append(". Cards: $it") }
                    if (outcome.phonesSynced > 0) append(". ${outcome.phonesSynced} parent number(s) saved")
                    if (outcome.phonesFailed > 0) append(". ${outcome.phonesFailed} parent number(s) refused - see Students")
                    outcome.phonesStoppedReason?.let { append(". Parent numbers: $it") }
                }
            }
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            gateAttendanceRepository.retryFailed()
            studentRepository.retryFailedCards()
            studentRepository.retryFailedPhones()
            uploadNow()
        }
    }

    fun downloadStudents() {
        if (_isDownloading.value) return
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadMessage.value = studentDownloadRepository.download().fold(
                onSuccess = { s ->
                    "Downloaded: ${s.added} new, ${s.updated} updated" + if (s.skipped > 0) ", ${s.skipped} skipped" else ""
                },
                onFailure = { it.message ?: "Download failed" },
            )
            _isDownloading.value = false
            refresh()
        }
    }
}
