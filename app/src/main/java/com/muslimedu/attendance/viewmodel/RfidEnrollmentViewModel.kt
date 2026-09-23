package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.rfid.RfidEvent
import com.muslimedu.attendance.rfid.RfidManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RfidEnrollmentUiState {
    data object SelectingStudent : RfidEnrollmentUiState()
    data class Listening(val student: StudentEntity) : RfidEnrollmentUiState()
    data class Success(val student: StudentEntity, val uid: String) : RfidEnrollmentUiState()
    data class Failed(val student: StudentEntity, val reason: String) : RfidEnrollmentUiState()
}

/**
 * Jumps straight into [RfidEnrollmentUiState.Listening] for one student (e.g.
 * tapped from the admin Student List's per-row card icon), skipping the
 * picker - same pattern as FaceEnrollmentScreen's PresetFaceTarget. Pass a
 * fresh [requestId] each time, even for the same student, so a second tap
 * while this screen is already showing that student's result re-enters
 * Listening instead of being a no-op.
 */
data class PresetRfidTarget(val student: StudentEntity, val requestId: Long)

/**
 * Carries a UID scanned elsewhere (the RFID scan screen's "Unknown Card" ->
 * "Assign to Student" action) into this screen's student picker - kept
 * separate from [PresetRfidTarget] rather than adding a field to it, since
 * that type is student-keyed and jumps straight to [RfidEnrollmentUiState.Listening];
 * this one still needs the picker (no student chosen yet), just skips the
 * "tap the card" step once one is, since the card is already known.
 */
data class PresetRfidUid(val uid: String, val requestId: Long)

@HiltViewModel
class RfidEnrollmentViewModel @Inject constructor(
    private val studentRepository: StudentRepository,
    private val rfidManager: RfidManager,
    private val photoCache: StudentPhotoCache,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidEnrollmentUiState>(RfidEnrollmentUiState.SelectingStudent)
    val uiState: StateFlow<RfidEnrollmentUiState> = _uiState.asStateFlow()

    private val _students = MutableStateFlow<List<StudentEntity>>(emptyList())
    val students: StateFlow<List<StudentEntity>> = _students.asStateFlow()

    val canSimulate: Boolean get() = rfidManager.canSimulate

    private var listenJob: Job? = null

    init {
        // Idempotent - a no-op if the scan screen's RfidViewModel already
        // registered this (same singleton), which it normally has by the
        // time an admin reaches this screen.
        rfidManager.register()
        // Re-read the picker's student list on every session change, not once
        // at construction - see TeacherDashboardViewModel's init. getAll() is
        // scoped to the logged-in account's school, so this is what stops the
        // previous account's students showing up in this picker.
        viewModelScope.launch {
            sessionManager.currentUser.collect {
                _students.value = studentRepository.getAll()
            }
        }
    }

    fun selectStudent(student: StudentEntity) {
        listenJob?.cancel()
        _uiState.value = RfidEnrollmentUiState.Listening(student)
        listenJob = viewModelScope.launch {
            val uid = rfidManager.events.filterIsInstance<RfidEvent.CardDetected>().first().uid
            assignCard(student, uid)
        }
    }

    /** Manual fallback for when tapping a physical card isn't practical right now. */
    fun assignManually(student: StudentEntity, uid: String) {
        listenJob?.cancel()
        viewModelScope.launch { assignCard(student, uid) }
    }

    /** Debug-only: triggers [com.muslimedu.attendance.rfid.MockRfidReader]. No-op with a real reader. */
    fun simulateScan() = rfidManager.simulateScan()

    /** The student's locally-cached photo, if one has been downloaded - never fetches over the network itself. */
    suspend fun loadPhoto(student: StudentEntity): Bitmap? =
        photoCache.loadCachedBitmap(student.schoolId, student.studentId)

    private suspend fun assignCard(student: StudentEntity, uid: String) {
        studentRepository.assignRfidCard(student, uid)
            .onSuccess { _uiState.value = RfidEnrollmentUiState.Success(student, uid) }
            .onFailure { e -> _uiState.value = RfidEnrollmentUiState.Failed(student, e.message ?: "Assignment failed") }
    }

    fun reset() {
        listenJob?.cancel()
        _uiState.value = RfidEnrollmentUiState.SelectingStudent
    }

    override fun onCleared() {
        listenJob?.cancel()
    }
}
