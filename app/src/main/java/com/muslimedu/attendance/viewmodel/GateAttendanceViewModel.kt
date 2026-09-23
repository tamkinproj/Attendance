package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.remote.dto.GateAttendanceRecordDto
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanStudentDto
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.rfid.RfidEvent
import com.muslimedu.attendance.rfid.RfidManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GateDirection(val apiValue: String, val label: String) {
    IN("in", "Gate In"),
    OUT("out", "Gate Out"),
}

sealed class GateScanUiState {
    data object Idle : GateScanUiState()
    data object Scanning : GateScanUiState()
    data class Success(val student: GateAttendanceScanStudentDto, val direction: GateDirection) : GateScanUiState()
    data class Failed(val message: String) : GateScanUiState()
}

/**
 * Admin-only, campus-wide gate in/out attendance - distinct from
 * [RfidViewModel], which is scoped to whichever class is currently active.
 * Reuses the same [RfidManager] singleton (a card tap here is resolved the
 * same way [com.muslimedu.attendance.viewmodel.RfidEnrollmentViewModel]
 * resolves one: locally, against [StudentRepository.findByRfid]) purely to
 * turn a UID into a `code` - the actual write always goes through
 * [GateAttendanceRepository], which calls the new campus-wide backend
 * endpoint directly. A card not found locally doesn't block this screen:
 * the admin can still type the student's `code` manually, since gate
 * attendance is meant to cover any student in the school, not just those
 * already RFID-enrolled on this device.
 */
@HiltViewModel
class GateAttendanceViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
    private val studentRepository: StudentRepository,
    private val rfidManager: RfidManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GateScanUiState>(GateScanUiState.Idle)
    val uiState: StateFlow<GateScanUiState> = _uiState.asStateFlow()

    private val _direction = MutableStateFlow(GateDirection.IN)
    val direction: StateFlow<GateDirection> = _direction.asStateFlow()

    private val _todayRecords = MutableStateFlow<List<GateAttendanceRecordDto>>(emptyList())
    val todayRecords: StateFlow<List<GateAttendanceRecordDto>> = _todayRecords.asStateFlow()

    private val _isLoadingToday = MutableStateFlow(false)
    val isLoadingToday: StateFlow<Boolean> = _isLoadingToday.asStateFlow()

    val canSimulate: Boolean get() = rfidManager.canSimulate

    private var listenJob: Job? = null

    init {
        // Idempotent - a no-op if the scan screen's RfidViewModel (or the
        // RFID enrollment screen) already registered this singleton.
        rfidManager.register()
        listenJob = viewModelScope.launch {
            rfidManager.events.filterIsInstance<RfidEvent.CardDetected>().collect { event ->
                val student = studentRepository.findByRfid(event.uid)
                if (student == null) {
                    _uiState.value = GateScanUiState.Failed(
                        "This card isn't assigned to any student cached on this device. Enter their code manually instead.",
                    )
                } else {
                    scanByCode(student.code)
                }
            }
        }
        refreshToday()
    }

    fun setDirection(value: GateDirection) {
        _direction.value = value
    }

    /** Debug-only: triggers [com.muslimedu.attendance.rfid.MockRfidReader]. No-op with a real reader. */
    fun simulateScan() = rfidManager.simulateScan()

    fun scanByCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return

        val dir = _direction.value
        viewModelScope.launch {
            _uiState.value = GateScanUiState.Scanning
            gateAttendanceRepository.scan(trimmed, dir.apiValue)
                .onSuccess { student -> _uiState.value = GateScanUiState.Success(student, dir) }
                .onFailure { e -> _uiState.value = GateScanUiState.Failed(e.message ?: "Gate scan failed") }
            refreshToday()
        }
    }

    fun dismissResult() {
        _uiState.value = GateScanUiState.Idle
    }

    fun refreshToday() {
        viewModelScope.launch {
            _isLoadingToday.value = true
            gateAttendanceRepository.today().onSuccess { _todayRecords.value = it }
            _isLoadingToday.value = false
        }
    }

    override fun onCleared() {
        listenJob?.cancel()
    }
}
