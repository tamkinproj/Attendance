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
import com.muslimedu.attendance.sync.ParentPhoneSyncManager
import com.muslimedu.attendance.sync.RfidCardSyncManager
import com.muslimedu.attendance.util.normalizePhMobile
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

/**
 * The parent number a student leaves the number step with ([phone] null =
 * none). [changed] is false when the admin kept or skipped it.
 * [serverNote] says whether the school server has it yet.
 */
data class RegisteredPhone(val phone: String?, val changed: Boolean, val serverNote: String)

sealed class RegistrationUiState {
    data object SelectingStudent : RegistrationUiState()

    /**
     * Waiting for the card. [error] is why the last card was refused - it
     * keeps listening for another. A student who already has a card keeps
     * it by itself after [StudentRegistrationViewModel.KEEP_MILLIS] unless a
     * new card is tapped, which replaces it.
     */
    data class TapCard(val student: StudentEntity, val error: String? = null) : RegistrationUiState()

    /**
     * The card is saved; now the face. [capturing]: the full-screen camera,
     * which retries by itself ([attempt] re-arms it, [hint] says why the last
     * try didn't take). Otherwise [error] (a face that belongs to another
     * student - retrying can't fix that) or, when the student [hasFace]
     * already, "keeping it" with a re-enroll option.
     */
    data class Face(
        val student: StudentEntity,
        val card: RegisteredCard,
        val hasFace: Boolean,
        val capturing: Boolean,
        val saving: Boolean = false,
        val error: String? = null,
        val attempt: Int = 0,
        val hint: String? = null,
    ) : RegistrationUiState()

    /**
     * The parent's mobile number - where the gate texts go. Optional: the
     * admin can skip it. [card] is null when the wizard was opened here
     * for a student with no card yet (from the Students list).
     */
    data class ParentPhone(
        val student: StudentEntity,
        val card: RegisteredCard?,
        val faceEnrolled: Boolean,
        val error: String? = null,
    ) : RegistrationUiState()

    data class Done(
        val student: StudentEntity,
        val card: RegisteredCard?,
        val faceEnrolled: Boolean,
        val phone: RegisteredPhone,
    ) : RegistrationUiState()
}

enum class RegistrationStart { CARD, FACE, PHONE }

/**
 * Opens the wizard on one student (from the Students list), skipping the
 * picker. [start] FACE goes straight to the face step when the student
 * already has a card (the list's face icon); PHONE to the parent number
 * (the list's phone icon).
 */
data class RegistrationTarget(val student: StudentEntity, val start: RegistrationStart)

/**
 * Register Card, Face & Number: one wizard instead of separate card, face and phone
 * screens - pick a student, tap their card, enroll their face, then enter
 * the parent's mobile number for the gate texts.
 *
 * - Card: only a card read by the reader is accepted (no typed UIDs), with
 *   the one-card-one-student rules of [StudentRepository.assignRfidCard]. A
 *   student who already has a card can keep it. The card is saved the moment
 *   it's read and sent to the server's card registry right away when online.
 * - Face: the same live auto-capture as the gate, saved by
 *   [FaceTemplateRepository.enroll], which refuses a face that matches
 *   another student's.
 * - Parent number: a Philippine mobile number, saved on the device at once
 *   and on the parent's server account as soon as it can be sent
 *   ([ParentPhoneSyncManager]). Optional - skipping it only means that
 *   student's parent gets no texts.
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
    private val parentPhoneSyncManager: ParentPhoneSyncManager,
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
            target.start == RegistrationStart.PHONE -> viewModelScope.launch { openPhoneStep(target.student) }
            // From the Students list's face icon: the admin came to (re-)enroll the face, so the camera opens at once.
            target.start == RegistrationStart.FACE && target.student.rfidCardNumber != null ->
                viewModelScope.launch { keepCard(target.student, forceCapture = true) }
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
            // Not good enough yet: the same camera tries again by itself.
            val retry = { hint: String -> current.copy(capturing = true, saving = false, attempt = current.attempt + 1, hint = hint) }
            _uiState.value = when (result) {
                is FaceEnrollResult.Success -> {
                    refreshCandidates()
                    phoneStep(student, current.card, faceEnrolled = true)
                }
                is FaceEnrollResult.NoFaceDetected -> retry("No face found - look straight at the camera")
                is FaceEnrollResult.LivenessTooLow -> retry("Hold still in good light - trying again")
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
        _uiState.value = state.copy(capturing = true, error = null, hint = null, attempt = state.attempt + 1)
    }

    /** Goes on without a new face: keeps the enrolled one, or leaves the student without one for now. */
    fun skipFace() {
        val state = _uiState.value as? RegistrationUiState.Face ?: return
        if (state.saving) return
        viewModelScope.launch { _uiState.value = phoneStep(state.student, state.card, faceEnrolled = state.hasFace) }
    }

    /**
     * Saves what the admin typed as the parent's number. Blank removes a
     * number the student had (or is the same as skipping when they had
     * none); the same number as before is kept as it is.
     */
    fun saveParentPhone(input: String) {
        val state = _uiState.value as? RegistrationUiState.ParentPhone ?: return
        val current = state.student.parentPhone
        val phone = if (input.isBlank()) null else normalizePhMobile(input)
        if (input.isNotBlank() && phone == null) {
            _uiState.value = state.copy(error = "That isn't a Philippine mobile number. Use 11 digits, e.g. 0917 123 4567.")
            return
        }
        if (phone == current) return skipParentPhone()
        viewModelScope.launch {
            studentRepository.setParentPhone(state.student, phone)
            val updated = studentRepository.reload(state.student) ?: state.student.copy(parentPhone = phone)
            _uiState.value = RegistrationUiState.Done(
                updated,
                state.card,
                state.faceEnrolled,
                RegisteredPhone(phone, changed = true, serverNote = "Sending to the school server..."),
            )
            refreshCandidates()
            launch { updatePhoneNote(updated, uploadPhoneAndDescribe(updated)) }
        }
    }

    /** Finishes with the number the student already has, or none. */
    fun skipParentPhone() {
        val state = _uiState.value as? RegistrationUiState.ParentPhone ?: return
        _uiState.value = RegistrationUiState.Done(
            state.student,
            state.card,
            state.faceEnrolled,
            RegisteredPhone(state.student.parentPhone, changed = false, serverNote = describePhoneSync(state.student, null)),
        )
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
            // Tapping a different card in this step is the admin's answer:
            // it replaces the old one (deactivated, shown on the summary).
            is CardAssignResult.NeedsReplace -> assignCard(student, uid, replace = true)
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

    private suspend fun keepCard(student: StudentEntity, forceCapture: Boolean = false) {
        val current = studentRepository.reload(student) ?: student
        val uid = current.rfidCardNumber ?: return listenForCard(current, error = null)
        startFaceStep(current, RegisteredCard(uid, replacedUid = null, kept = true, serverNote = describeCardSync(current, null)), forceCapture)
    }

    private suspend fun startFaceStep(student: StudentEntity, card: RegisteredCard, forceCapture: Boolean = false) {
        val hasFace = faceTemplateRepository.hasTemplate(student.schoolId, student.studentId)
        _uiState.value = RegistrationUiState.Face(student, card, hasFace = hasFace, capturing = !hasFace || forceCapture)
    }

    /** Re-read so the step shows the number a download or an earlier visit left. */
    private suspend fun phoneStep(student: StudentEntity, card: RegisteredCard?, faceEnrolled: Boolean) =
        RegistrationUiState.ParentPhone(studentRepository.reload(student) ?: student, card, faceEnrolled)

    /** Straight to the number (the Students list's phone icon), with whatever card and face the student has. */
    private suspend fun openPhoneStep(student: StudentEntity) {
        val current = studentRepository.reload(student) ?: student
        val card = current.rfidCardNumber?.let {
            RegisteredCard(it, replacedUid = null, kept = true, serverNote = describeCardSync(current, null))
        }
        val hasFace = faceTemplateRepository.hasTemplate(current.schoolId, current.studentId)
        _uiState.value = RegistrationUiState.ParentPhone(current, card, hasFace)
    }

    /** The upload finishes after the wizard has moved on - update whichever step is showing this student. */
    private fun updateCardNote(student: StudentEntity, note: String) {
        _uiState.value = when (val state = _uiState.value) {
            is RegistrationUiState.Face ->
                if (state.student.id == student.id) state.copy(card = state.card.copy(serverNote = note)) else state
            is RegistrationUiState.ParentPhone ->
                if (state.student.id == student.id) state.copy(card = state.card?.copy(serverNote = note)) else state
            is RegistrationUiState.Done ->
                if (state.student.id == student.id) state.copy(card = state.card?.copy(serverNote = note)) else state
            else -> state
        }
    }

    private fun updatePhoneNote(student: StudentEntity, note: String) {
        val state = _uiState.value as? RegistrationUiState.Done ?: return
        if (state.student.id == student.id) _uiState.value = state.copy(phone = state.phone.copy(serverNote = note))
    }

    private suspend fun uploadPhoneAndDescribe(student: StudentEntity): String {
        gateSyncScheduler.syncWhenOnline()
        val outcome = parentPhoneSyncManager.flush()
        refreshCandidates()
        return describePhoneSync(studentRepository.reload(student) ?: student, outcome.stoppedReason)
    }

    private fun describePhoneSync(student: StudentEntity, stoppedReason: String?): String = when (student.phoneSyncStatus) {
        StudentEntity.RFID_SYNCED ->
            if (student.parentPhone != null) "Saved on the school server - gate texts go to this number." else "No number on the school server."
        StudentEntity.RFID_FAILED -> "The school server refused it: ${student.phoneSyncError ?: "unknown reason"}"
        else -> "Saved on this device. " + (stoppedReason ?: "It will sync with the school server automatically.")
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

    companion object {
        /** How long a step that already has what it needs (card, face, number) waits before keeping it. */
        const val KEEP_MILLIS = 6_000L

        /** How long the Done page shows before the next student. */
        const val DONE_MILLIS = 5_000L

        /** After the last digit of a valid number, before it saves by itself. */
        const val PHONE_SAVE_DELAY_MILLIS = 1_200L
    }
}
