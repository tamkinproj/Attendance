package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.repository.AttendanceRepository
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.FaceVerificationResult
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.rfid.RfidEvent
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.sync.SyncQueueManager
import com.muslimedu.attendance.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ScanUiState {
    data object Listening : ScanUiState()
    data class Matched(val student: StudentEntity, val uid: String) : ScanUiState()
    data class DuplicateScan(val student: StudentEntity, val existingCheckInTime: String) : ScanUiState()
    data class AwaitingFaceVerification(val student: StudentEntity, val uid: String) : ScanUiState()
    data class VerifyingFace(val student: StudentEntity, val uid: String) : ScanUiState()
    data class FaceVerificationFailed(val student: StudentEntity, val uid: String, val reason: String) : ScanUiState()
    data class UnknownCard(val uid: String) : ScanUiState()
    data class ReaderError(val message: String) : ScanUiState()
}

@HiltViewModel
class RfidViewModel @Inject constructor(
    private val rfidManager: RfidManager,
    private val studentRepository: StudentRepository,
    private val attendanceRepository: AttendanceRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val syncQueueManager: SyncQueueManager,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Listening)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /** Whether a reader is physically attached, for the scan screen's indicator. */
    val readerStatus: StateFlow<ReaderStatus> = rfidManager.status

    /**
     * Whether the device currently has a validated internet connection, for
     * the scan screen's indicator. Purely informational - a scan is recorded
     * locally and queued for sync either way, online or off (see
     * [SyncQueueManager]); this only tells the teacher whether to expect it
     * synced right away.
     */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val canSimulate: Boolean get() = rfidManager.canSimulate

    init {
        rfidManager.register()
        viewModelScope.launch {
            studentRepository.seedSampleDataIfEmpty()
        }
        viewModelScope.launch {
            rfidManager.events.collect { event -> handleEvent(event) }
        }
    }

    private suspend fun handleEvent(event: RfidEvent) {
        when (event) {
            is RfidEvent.CardDetected -> onCardDetected(event.uid)
            is RfidEvent.Error -> _uiState.value = ScanUiState.ReaderError(event.message)
            // Connection state comes from RfidManager.status, which tracks what
            // is actually plugged in rather than whether a reader has spoken.
            is RfidEvent.Connected, is RfidEvent.Disconnected -> Unit
        }
    }

    private suspend fun onCardDetected(uid: String) {
        val student = studentRepository.findByRfid(uid)
        if (student == null) {
            _uiState.value = ScanUiState.UnknownCard(uid)
            return
        }

        val existing = attendanceRepository.findExistingToday(student.schoolId, student.studentId)
        if (existing != null) {
            _uiState.value = ScanUiState.DuplicateScan(student, existing.checkInTime)
            return
        }

        // Only require face verification for students who actually have an
        // enrolled template - everyone else keeps working exactly as before
        // (RFID-only), so rolling this out doesn't block anyone who hasn't
        // enrolled yet.
        if (faceTemplateRepository.hasTemplate(student.schoolId, student.studentId)) {
            _uiState.value = ScanUiState.AwaitingFaceVerification(student, uid)
        } else {
            recordAndShowMatched(student, uid, verifiedByFace = false, faceMatchScore = null)
        }
    }

    /** Called by the UI once the camera capture returns a frame. */
    fun onFaceCaptured(bitmap: Bitmap) {
        val state = _uiState.value
        if (state !is ScanUiState.AwaitingFaceVerification && state !is ScanUiState.FaceVerificationFailed) return
        val (student, uid) = studentAndUid(state) ?: return

        viewModelScope.launch {
            _uiState.value = ScanUiState.VerifyingFace(student, uid)
            when (val result = faceTemplateRepository.verify(student.schoolId, student.studentId, bitmap)) {
                is FaceVerificationResult.Matched ->
                    recordAndShowMatched(student, uid, verifiedByFace = true, faceMatchScore = result.score)
                is FaceVerificationResult.NotMatched ->
                    _uiState.value = ScanUiState.FaceVerificationFailed(
                        student, uid, "Face did not match (score %.2f)".format(result.score),
                    )
                is FaceVerificationResult.NoFaceDetected ->
                    _uiState.value = ScanUiState.FaceVerificationFailed(student, uid, "No face detected - try again")
                // Shouldn't happen given the hasTemplate() check before entering this
                // state, but degrade to RFID-only rather than get stuck if it does.
                is FaceVerificationResult.NoTemplateEnrolled ->
                    recordAndShowMatched(student, uid, verifiedByFace = false, faceMatchScore = null)
            }
        }
    }

    private fun studentAndUid(state: ScanUiState): Pair<StudentEntity, String>? = when (state) {
        is ScanUiState.AwaitingFaceVerification -> state.student to state.uid
        is ScanUiState.FaceVerificationFailed -> state.student to state.uid
        else -> null
    }

    private suspend fun recordAndShowMatched(
        student: StudentEntity,
        uid: String,
        verifiedByFace: Boolean,
        faceMatchScore: Float?,
        manualOverride: Boolean = false,
    ) {
        attendanceRepository.recordScan(
            student = student,
            rfidUid = uid,
            verifiedByRfid = true,
            verifiedByFace = verifiedByFace,
            faceMatchScore = faceMatchScore,
            manualOverride = manualOverride,
        )
        _uiState.value = ScanUiState.Matched(student, uid)

        // Best-effort, non-blocking: try to sync right away if we're online.
        // If this fails or we're offline, SyncWorker's periodic run still
        // picks the record up later - it's already saved locally either way.
        viewModelScope.launch {
            try {
                syncQueueManager.flush()
            } catch (e: Exception) {
                // Swallow - this is opportunistic, not the only path to sync.
            }
        }
    }

    fun dismissResult() {
        _uiState.value = ScanUiState.Listening
    }

    /** Debug-only: triggers [com.muslimedu.attendance.rfid.MockRfidReader]. No-op with a real reader. */
    fun simulateScan(uid: String? = null) = rfidManager.simulateScan(uid)
}
