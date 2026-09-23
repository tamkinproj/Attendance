package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.FaceVerificationResult
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.GateRecordResult
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.rfid.RfidEvent
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.sync.GateSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GateDirection(val apiValue: String, val label: String) {
    IN(GateScanEntity.DIRECTION_IN, "Gate In"),
    OUT(GateScanEntity.DIRECTION_OUT, "Gate Out"),
}

sealed class GateScanUiState {
    data object Idle : GateScanUiState()
    data class AwaitingFace(val student: StudentEntity, val direction: GateDirection, val failureReason: String?) : GateScanUiState()
    data class VerifyingFace(val student: StudentEntity) : GateScanUiState()
    data class Recorded(val scan: GateScanEntity, val isDuplicate: Boolean, val onDevice: Boolean) : GateScanUiState()
    data class Failed(val message: String) : GateScanUiState()
}

/** One student's latest gate event today on this device, with their first "in". */
data class GateActivityRow(
    val code: String,
    val name: String?,
    val lastDirection: String,
    val lastTime: String,
    val firstInTime: String?,
    val syncStatus: String,
)

/**
 * The gate screen: works with no login and no network. A card tap (or a
 * typed code) is resolved against this device's students; if that student
 * has a face enrolled on this device they must pass the camera check first,
 * same rule the classroom flow used. The scan is then saved locally and an
 * upload is attempted in the background - which does nothing until an admin
 * has signed in on the Sync screen.
 *
 * A typed code that isn't on this device is still recorded (the backend
 * resolves gate scans by code school-wide, and will reject a wrong one at
 * sync time, visibly on the Sync screen) - a student missing from this
 * device's cache shouldn't be stuck outside the gate.
 */
@HiltViewModel
class GateAttendanceViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
    private val studentRepository: StudentRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val gateSyncManager: GateSyncManager,
    private val rfidManager: RfidManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GateScanUiState>(GateScanUiState.Idle)
    val uiState: StateFlow<GateScanUiState> = _uiState.asStateFlow()

    private val _direction = MutableStateFlow(GateDirection.IN)
    val direction: StateFlow<GateDirection> = _direction.asStateFlow()

    val todayActivity: StateFlow<List<GateActivityRow>> = gateAttendanceRepository.observeToday()
        .map(::toActivityRows)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingUploadCount: StateFlow<Int> = gateAttendanceRepository.observeCount(GateScanEntity.SYNC_PENDING)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val canSimulate: Boolean get() = rfidManager.canSimulate
    val readerStatus = rfidManager.status

    private var autoDismissJob: Job? = null

    init {
        rfidManager.register()
        viewModelScope.launch {
            rfidManager.events.filterIsInstance<RfidEvent.CardDetected>().collect { event -> onCard(event.uid) }
        }
    }

    fun setDirection(value: GateDirection) {
        _direction.value = value
    }

    /** Debug-only: triggers [com.muslimedu.attendance.rfid.MockRfidReader]. No-op with a real reader. */
    fun simulateScan() = rfidManager.simulateScan()

    private suspend fun onCard(uid: String) {
        if (isBusyWithFace()) return
        val student = studentRepository.findByRfid(uid)
        if (student == null) {
            showFailure("This card isn't assigned to any student on this device. Enter their code, or assign the card in Admin.")
        } else {
            resolve(student)
        }
    }

    fun scanByCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || isBusyWithFace()) return
        viewModelScope.launch {
            val student = studentRepository.findByCode(trimmed)
            if (student != null) {
                resolve(student)
            } else {
                record(code = trimmed, name = null, direction = _direction.value, verifiedByFace = false, score = null, onDevice = false)
            }
        }
    }

    private suspend fun resolve(student: StudentEntity) {
        val dir = _direction.value
        if (faceTemplateRepository.hasTemplate(student.schoolId, student.studentId)) {
            autoDismissJob?.cancel()
            _uiState.value = GateScanUiState.AwaitingFace(student, dir, failureReason = null)
        } else {
            record(student.code, student.name, dir, verifiedByFace = false, score = null, onDevice = true)
        }
    }

    fun onFaceCaptured(bitmap: Bitmap) {
        val state = _uiState.value as? GateScanUiState.AwaitingFace ?: return
        viewModelScope.launch {
            _uiState.value = GateScanUiState.VerifyingFace(state.student)
            val student = state.student
            when (val result = faceTemplateRepository.verify(student.schoolId, student.studentId, bitmap)) {
                is FaceVerificationResult.Matched ->
                    record(student.code, student.name, state.direction, verifiedByFace = true, score = result.score, onDevice = true)
                is FaceVerificationResult.NotMatched ->
                    _uiState.value = state.copy(failureReason = "Face doesn't match (score %.2f) - try again".format(result.score))
                is FaceVerificationResult.NoFaceDetected ->
                    _uiState.value = state.copy(failureReason = "No face detected - try again")
                // Template removed between the check and now - nothing left to verify against.
                is FaceVerificationResult.NoTemplateEnrolled ->
                    record(student.code, student.name, state.direction, verifiedByFace = false, score = null, onDevice = true)
            }
        }
    }

    /** Abandons a face check - nothing is recorded, so a student who fails it isn't let through by accident. */
    fun cancelFaceCheck() {
        if (isBusyWithFace()) _uiState.value = GateScanUiState.Idle
    }

    fun dismissResult() {
        autoDismissJob?.cancel()
        _uiState.value = GateScanUiState.Idle
    }

    private suspend fun record(
        code: String,
        name: String?,
        direction: GateDirection,
        verifiedByFace: Boolean,
        score: Float?,
        onDevice: Boolean,
    ) {
        val result = gateAttendanceRepository.record(code, name, direction.apiValue, verifiedByFace, score)
        val state = when (result) {
            is GateRecordResult.Recorded -> GateScanUiState.Recorded(result.scan, isDuplicate = false, onDevice = onDevice)
            is GateRecordResult.Duplicate -> GateScanUiState.Recorded(result.existing, isDuplicate = true, onDevice = onDevice)
        }
        _uiState.value = state
        // Back to "ready" on its own so a queue of students keeps moving.
        autoDismissJob?.cancel()
        autoDismissJob = viewModelScope.launch {
            delay(AUTO_DISMISS_MILLIS)
            if (_uiState.value == state) _uiState.value = GateScanUiState.Idle
        }
        if (result is GateRecordResult.Recorded) {
            viewModelScope.launch { gateSyncManager.flush() }
        }
    }

    private fun showFailure(message: String) {
        autoDismissJob?.cancel()
        _uiState.value = GateScanUiState.Failed(message)
    }

    private fun isBusyWithFace(): Boolean =
        _uiState.value is GateScanUiState.AwaitingFace || _uiState.value is GateScanUiState.VerifyingFace

    private fun toActivityRows(scans: List<GateScanEntity>): List<GateActivityRow> =
        // scans are newest first, so the first per code is the latest event.
        scans.groupBy { it.studentCode }.map { (code, events) ->
            val latest = events.first()
            GateActivityRow(
                code = code,
                name = events.firstNotNullOfOrNull { it.studentName },
                lastDirection = latest.direction,
                lastTime = latest.scanTime,
                firstInTime = events.lastOrNull { it.direction == GateScanEntity.DIRECTION_IN }?.scanTime,
                syncStatus = latest.syncStatus,
            )
        }

    companion object {
        private const val AUTO_DISMISS_MILLIS = 2_500L
    }
}
