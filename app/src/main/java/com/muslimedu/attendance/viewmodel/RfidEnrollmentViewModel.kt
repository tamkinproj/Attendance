package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.CardAssignResult
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.rfid.RfidEvent
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.rfid.normalizeRfidUid
import com.muslimedu.attendance.sync.GateSyncScheduler
import com.muslimedu.attendance.sync.RfidCardSyncManager
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

    /** The student already has [oldUid]; registering [newUid] would deactivate it. */
    data class ConfirmReplace(val student: StudentEntity, val newUid: String, val oldUid: String) : RfidEnrollmentUiState()

    /** [serverNote] says whether the web admin's card registry has it yet. */
    data class Success(val student: StudentEntity, val uid: String, val replacedUid: String?, val serverNote: String) :
        RfidEnrollmentUiState()

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
 * Carries a UID read by the reader elsewhere into this screen's student
 * picker, skipping the "tap the card" step once a student is chosen.
 */
data class PresetRfidUid(val uid: String, val requestId: Long)

/**
 * RFID registration: attach a physical card to an existing student. Only a
 * card read by the reader is accepted - there is no typed-in UID. See
 * [StudentRepository.assignRfidCard] for the one-card-one-student rules;
 * the result is sent to the server's card registry right away when online.
 */
@HiltViewModel
class RfidEnrollmentViewModel @Inject constructor(
    private val studentRepository: StudentRepository,
    private val rfidManager: RfidManager,
    private val photoCache: StudentPhotoCache,
    private val deviceSettings: DeviceSettings,
    private val rfidCardSyncManager: RfidCardSyncManager,
    private val gateSyncScheduler: GateSyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidEnrollmentUiState>(RfidEnrollmentUiState.SelectingStudent)
    val uiState: StateFlow<RfidEnrollmentUiState> = _uiState.asStateFlow()

    private val _students = MutableStateFlow<List<StudentEntity>>(emptyList())
    val students: StateFlow<List<StudentEntity>> = _students.asStateFlow()

    private var listenJob: Job? = null

    init {
        rfidManager.register()
        // Re-read when the device's school changes - getAll() is scoped to it.
        viewModelScope.launch {
            deviceSettings.schoolId.collect {
                _students.value = studentRepository.getAll()
            }
        }
    }

    fun selectStudent(student: StudentEntity) {
        listenJob?.cancel()
        _uiState.value = RfidEnrollmentUiState.Listening(student)
        listenJob = viewModelScope.launch {
            val uid = rfidManager.events.filterIsInstance<RfidEvent.CardDetected>().first().uid
            assignCard(student, uid, replace = false)
        }
    }

    /** For a UID the reader already read elsewhere ([PresetRfidUid]). */
    fun assignManually(student: StudentEntity, uid: String) {
        listenJob?.cancel()
        viewModelScope.launch { assignCard(student, uid, replace = false) }
    }

    fun confirmReplace() {
        val state = _uiState.value as? RfidEnrollmentUiState.ConfirmReplace ?: return
        viewModelScope.launch { assignCard(state.student, state.newUid, replace = true) }
    }

    /** The student's locally-cached photo, if one has been downloaded - never fetches over the network itself. */
    suspend fun loadPhoto(student: StudentEntity): Bitmap? =
        photoCache.loadCachedBitmap(student.schoolId, student.studentId)

    private suspend fun assignCard(student: StudentEntity, rawUid: String, replace: Boolean) {
        val uid = normalizeRfidUid(rawUid)
        when (val result = studentRepository.assignRfidCard(student, uid, replace)) {
            is CardAssignResult.NeedsReplace ->
                _uiState.value = RfidEnrollmentUiState.ConfirmReplace(student, uid, result.currentUid)
            is CardAssignResult.OwnedByOther ->
                _uiState.value = RfidEnrollmentUiState.Failed(
                    student,
                    "Card $uid is registered to ${result.owner.name} (${result.owner.code}). " +
                        "Deactivate it on that student first (Admin > Students).",
                )
            is CardAssignResult.Assigned -> {
                _students.value = studentRepository.getAll()
                _uiState.value = RfidEnrollmentUiState.Success(student, uid, result.replacedUid, serverNote = "Sending to the web admin...")
                val note = uploadAndDescribe(student)
                val current = _uiState.value
                if (current is RfidEnrollmentUiState.Success && current.student.id == student.id) {
                    _uiState.value = current.copy(serverNote = note)
                }
            }
        }
    }

    /** Uploads right away when possible; otherwise it goes up as soon as the device is online. */
    private suspend fun uploadAndDescribe(student: StudentEntity): String {
        gateSyncScheduler.syncWhenOnline()
        val outcome = rfidCardSyncManager.flush()
        val updated = studentRepository.reload(student)
        return when (updated?.rfidSyncStatus) {
            StudentEntity.RFID_SYNCED -> "Registered with the web admin."
            StudentEntity.RFID_FAILED -> "The web admin refused it: ${updated.rfidSyncError ?: "unknown reason"}"
            else -> "Saved on this device. " + (outcome.stoppedReason ?: "It will sync with the web admin automatically.")
        }
    }

    fun reset() {
        listenJob?.cancel()
        _uiState.value = RfidEnrollmentUiState.SelectingStudent
    }

    override fun onCleared() {
        listenJob?.cancel()
    }
}
