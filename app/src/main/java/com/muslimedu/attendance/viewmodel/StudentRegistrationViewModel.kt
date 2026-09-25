package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.CardAssignResult
import com.muslimedu.attendance.data.repository.FaceEnrollResult
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.data.session.SessionManager
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

/** A student in the wizard's picker, with what they still need. */
data class RegistrationCandidate(val student: StudentEntity, val hasFace: Boolean)

/** The card a student leaves the card step with. [serverNote] says whether the web admin's registry has it yet. */
data class RegisteredCard(val uid: String, val replacedUid: String?, val kept: Boolean, val serverNote: String)

sealed class RegistrationUiState {
    data object SelectingStudent : RegistrationUiState()

    /** Waiting for the card. [error] is why the last card was refused - it keeps listening for another. */
    data class TapCard(val student: StudentEntity, val error: String? = null) : RegistrationUiState()

    /** The student already has [oldUid]; registering [newUid] deactivates it. */
    data class ConfirmReplace(val student: StudentEntity, val newUid: String, val oldUid: String) : RegistrationUiState()

    /**
     * The card is saved; now the face. [capturing] shows the camera. Otherwise
     * the admin sees [error] (a refused capture) or, when the student
     * [hasFace] already, the keep / re-enroll choice. [attempt] gives each
     * capture a fresh camera - the capture view fires once.
     */
    data class Face(
        val student: StudentEntity,
        val card: RegisteredCard,
        val hasFace: Boolean,
        val capturing: Boolean,
        val saving: Boolean = false,
        val error: String? = null,
        val attempt: Int = 0,
    ) : RegistrationUiState()

    data class Done(val student: StudentEntity, val card: RegisteredCard, val faceEnrolled: Boolean) : RegistrationUiState()
}

/**
 * Opens the wizard on one student (from the Students list), skipping the
 * picker. [startAtFace] goes straight to the face step when the student
 * already has a card (the list's face icon).
 */
data class RegistrationTarget(val student: StudentEntity, val startAtFace: Boolean)

/**
 * Register Card & Face: one wizard instead of separate card and face
 * screens - pick a student, tap their card, then enroll their face.
 *
 * - Card: only a card read by the reader is accepted (no typed UIDs), with
 *   the one-card-one-student rules of [StudentRepository.assignRfidCard]. A
 *   student who already has a card can keep it. The card is saved the moment
 *   it's read and sent to the server's card registry right away when online.
 * - Face: the same live auto-capture as the gate, saved by
 *   [FaceTemplateRepository.enroll], which refuses a face that matches
 *   another student's.
 *
 * Like the gate scan view model, this outlives the screen, so it only
 * listens to the reader while the screen is open ([enter]/[leave]) - a card
 * tapped at the gate must never be registered to the student left open here.
 */
@HiltViewModel
class StudentRegistrationViewModel @Inject constructor(
    private val studentRepository: StudentRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val rfidManager: RfidManager,
    private val photoCache: StudentPhotoCache,
    private val deviceSettings: DeviceSettings,
    private val sessionManager: SessionManager,
    private val rfidCardSyncManager: RfidCardSyncManager,
    private val gateSyncScheduler: GateSyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RegistrationUiState>(RegistrationUiState.SelectingStudent)
    val uiState: StateFlow<RegistrationUiState> = _uiState.asStateFlow()

    private val _candidates = MutableStateFlow<List<RegistrationCandidate>>(emptyList())
    val candidates: StateFlow<List<RegistrationCandidate>> = _candidates.asStateFlow()

    /** Whether a reader is plugged in - shown on the card step. */
    val readerStatus = rfidManager.status

    private var listenJob: Job? = null
    private var openedRequestId: Long? = null

    init {
        rfidManager.register()
        // Re-read when the device's school changes - getAll() is scoped to it.
        viewModelScope.launch {
            deviceSettings.schoolId.collect { refreshCandidates() }
        }
    }

    /**
     * The screen opened. A new [requestId] starts over (at [target], or the
     * picker); the same one - the activity was recreated - carries on where
     * the admin was.
     */
    fun enter(requestId: Long, target: RegistrationTarget?) {
        refreshCandidates()
        if (requestId == openedRequestId) {
            (_uiState.value as? RegistrationUiState.TapCard)?.let { listenForCard(it.student, it.error) }
            return
        }
        openedRequestId = requestId
        when {
            target == null -> reset()
            target.startAtFace && target.student.rfidCardNumber != null -> viewModelScope.launch { keepCard(target.student) }
            else -> selectStudent(target.student)
        }
    }

    fun leave() {
        listenJob?.cancel()
        listenJob = null
    }

    fun selectStudent(student: StudentEntity) {
        viewModelScope.launch { listenForCard(studentRepository.reload(student) ?: student, error = null) }
    }

    /** The student keeps the card they already have and goes on to the face. */
    fun keepCurrentCard() {
        val state = _uiState.value as? RegistrationUiState.TapCard ?: return
        leave()
        viewModelScope.launch { keepCard(state.student) }
    }

    fun confirmReplace() {
        val state = _uiState.value as? RegistrationUiState.ConfirmReplace ?: return
        viewModelScope.launch { assignCard(state.student, state.newUid, replace = true) }
    }

    fun cancelReplace() {
        val state = _uiState.value as? RegistrationUiState.ConfirmReplace ?: return
        listenForCard(state.student, error = null)
    }

    fun onFaceCaptured(bitmap: Bitmap) {
        val state = _uiState.value as? RegistrationUiState.Face ?: return
        if (!state.capturing || state.saving) return
        _uiState.value = state.copy(saving = true)
        viewModelScope.launch {
            val student = state.student
            val result = faceTemplateRepository.enroll(
                schoolId = student.schoolId,
                studentId = student.studentId,
                bitmap = bitmap,
                enrolledBy = sessionManager.currentUser.value?.email ?: "device",
            )
            // The admin may have left or moved on while this was saving.
            val current = _uiState.value as? RegistrationUiState.Face ?: return@launch
            if (current.student.id != student.id) return@launch
            val refused = { reason: String -> current.copy(capturing = false, saving = false, error = reason) }
            _uiState.value = when (result) {
                is FaceEnrollResult.Success -> {
                    refreshCandidates()
                    RegistrationUiState.Done(student, current.card, faceEnrolled = true)
                }
                is FaceEnrollResult.NoFaceDetected -> refused("No face detected. Look straight at the camera and try again.")
                is FaceEnrollResult.LivenessTooLow ->
                    refused("Liveness check failed (score %.2f). Try again in better lighting.".format(result.score))
                is FaceEnrollResult.AlreadyEnrolled -> {
                    val other = studentRepository.find(student.schoolId, result.studentId)
                    val who = other?.let { "${it.name} (${it.code})" } ?: "another student"
                    refused("This face is already enrolled for $who. Each student needs their own face - nothing was saved.")
                }
            }
        }
    }

    /** Opens the camera again - after a refused capture, or to replace a face already enrolled. */
    fun captureFace() {
        val state = _uiState.value as? RegistrationUiState.Face ?: return
        if (state.saving) return
        _uiState.value = state.copy(capturing = true, error = null, attempt = state.attempt + 1)
    }

    /** Finishes without a new face: keeps the enrolled one, or leaves the student without one for now. */
    fun skipFace() {
        val state = _uiState.value as? RegistrationUiState.Face ?: return
        if (state.saving) return
        _uiState.value = RegistrationUiState.Done(state.student, state.card, faceEnrolled = state.hasFace)
    }

    fun reset() {
        leave()
        _uiState.value = RegistrationUiState.SelectingStudent
    }

    /** The student's locally-cached photo, if one has been downloaded - never fetches over the network itself. */
    suspend fun loadPhoto(student: StudentEntity): Bitmap? =
        photoCache.loadCachedBitmap(student.schoolId, student.studentId)

    private fun listenForCard(student: StudentEntity, error: String?) {
        leave()
        _uiState.value = RegistrationUiState.TapCard(student, error)
        listenJob = viewModelScope.launch {
            val uid = rfidManager.events.filterIsInstance<RfidEvent.CardDetected>().first().uid
            listenJob = null
            assignCard(student, uid, replace = false)
        }
    }

    private suspend fun assignCard(student: StudentEntity, rawUid: String, replace: Boolean) {
        val uid = normalizeRfidUid(rawUid)
        when (val result = studentRepository.assignRfidCard(student, uid, replace)) {
            is CardAssignResult.NeedsReplace ->
                _uiState.value = RegistrationUiState.ConfirmReplace(student, uid, result.currentUid)
            is CardAssignResult.OwnedByOther -> listenForCard(
                student,
                error = "Card $uid is registered to ${result.owner.name} (${result.owner.code}). " +
                    "Tap a different card, or deactivate it on that student first (Admin > Students).",
            )
            is CardAssignResult.Assigned -> {
                val updated = studentRepository.reload(student) ?: student.copy(rfidCardNumber = uid)
                val card = RegisteredCard(
                    uid = uid,
                    replacedUid = result.replacedUid,
                    kept = student.rfidCardNumber == uid,
                    serverNote = "Sending to the web admin...",
                )
                startFaceStep(updated, card)
                refreshCandidates()
                viewModelScope.launch { updateCardNote(updated, uploadAndDescribe(updated)) }
            }
        }
    }

    private suspend fun keepCard(student: StudentEntity) {
        val current = studentRepository.reload(student) ?: student
        val uid = current.rfidCardNumber ?: return listenForCard(current, error = null)
        startFaceStep(current, RegisteredCard(uid, replacedUid = null, kept = true, serverNote = describeCardSync(current, null)))
    }

    private suspend fun startFaceStep(student: StudentEntity, card: RegisteredCard) {
        val hasFace = faceTemplateRepository.hasTemplate(student.schoolId, student.studentId)
        _uiState.value = RegistrationUiState.Face(student, card, hasFace = hasFace, capturing = !hasFace)
    }

    /** The upload finishes after the wizard has moved on - update whichever step is showing this student. */
    private fun updateCardNote(student: StudentEntity, note: String) {
        _uiState.value = when (val state = _uiState.value) {
            is RegistrationUiState.Face ->
                if (state.student.id == student.id) state.copy(card = state.card.copy(serverNote = note)) else state
            is RegistrationUiState.Done ->
                if (state.student.id == student.id) state.copy(card = state.card.copy(serverNote = note)) else state
            else -> state
        }
    }

    /** Uploads right away when possible; otherwise it goes up as soon as the device is online. */
    private suspend fun uploadAndDescribe(student: StudentEntity): String {
        gateSyncScheduler.syncWhenOnline()
        val outcome = rfidCardSyncManager.flush()
        return describeCardSync(studentRepository.reload(student) ?: student, outcome.stoppedReason)
    }

    private fun describeCardSync(student: StudentEntity, stoppedReason: String?): String = when (student.rfidSyncStatus) {
        StudentEntity.RFID_SYNCED -> "Registered with the web admin."
        StudentEntity.RFID_FAILED -> "The web admin refused it: ${student.rfidSyncError ?: "unknown reason"}"
        else -> "Saved on this device. " + (stoppedReason ?: "It will sync with the web admin automatically.")
    }

    private fun refreshCandidates() {
        viewModelScope.launch {
            val faces = faceTemplateRepository.enrolledKeys()
            _candidates.value = studentRepository.getAll().map { student ->
                RegistrationCandidate(student, hasFace = (student.schoolId to student.studentId) in faces)
            }
        }
    }

    override fun onCleared() {
        leave()
    }
}
