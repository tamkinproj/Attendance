package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.FaceVerificationResult
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.GateRecordResult
import com.muslimedu.attendance.data.repository.GateScanCheck
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.rfid.RfidEvent
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.rfid.normalizeRfidUid
import com.muslimedu.attendance.sync.GateSyncManager
import com.muslimedu.attendance.sync.GateSyncOutcome
import com.muslimedu.attendance.sync.GateSyncScheduler
import com.muslimedu.attendance.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

enum class GateDirection(val apiValue: String, val label: String) {
    IN(GateScanEntity.DIRECTION_IN, "Coming In"),
    OUT(GateScanEntity.DIRECTION_OUT, "Going Out"),
    ;

    val opposite: GateDirection get() = if (this == IN) OUT else IN
}

sealed class GateScanState {
    /** "Tap your RFID card/tag on the reader." */
    data object Ready : GateScanState()

    /** Step 1 done (card -> student), step 2 running: the existing live face check. [attempt] re-keys the camera. */
    data class FaceCheck(val student: StudentEntity, val rfidUid: String, val attempt: Int) : GateScanState()

    data class Verifying(val student: StudentEntity, val rfidUid: String) : GateScanState()

    /** Card read AND face confirmed - the attendance record, scan [number] of [perDay] in its direction today. */
    data class Recorded(val scan: GateScanEntity, val number: Int, val perDay: Int) : GateScanState()

    /** The gate schedule refused this scan (see [GateScanCheck]) - nothing saved, no face step. */
    data class NotAllowed(val student: StudentEntity, val direction: GateDirection, val check: GateScanCheck) : GateScanState()

    /** Face not confirmed: nothing recorded as attendance. */
    data class FaceFailed(val student: StudentEntity, val rfidUid: String, val reason: String, val attempt: Int) : GateScanState()

    /** The student has no face enrolled on this device, so they can't be confirmed - nothing recorded. */
    data class NoFaceEnrolled(val student: StudentEntity) : GateScanState()

    /** The card isn't registered to any student on this device. */
    data class UnknownCard(val uid: String) : GateScanState()
}

sealed class LeaveDialogState {
    data object Hidden : LeaveDialogState()
    data class Prompt(val unsyncedCount: Int) : LeaveDialogState()
    data object Syncing : LeaveDialogState()

    /** Sync couldn't finish; the records stay saved and sync automatically later. Leaving after OK. */
    data class NotFinished(val message: String) : LeaveDialogState()
}

/** One result on this screen since it was opened - the attendant's running log. */
data class SessionResult(val name: String, val time: String, val success: Boolean, val detail: String)

/**
 * The dedicated "RFID Coming In" / "RFID Going Out" screen. One direction
 * per visit ([enter]); every student goes through:
 *
 * 1. RFID identification - the card is looked up on this device.
 * 2. Face confirmation - the existing [FaceTemplateRepository.verify] check,
 *    with the existing live camera ([com.muslimedu.attendance.ui.components.LiveFaceCaptureView]).
 * 3. Only if the face matches is attendance recorded. A failed check is
 *    kept as a rejected attempt - never attendance - and can be retried.
 *
 * A student with no face enrolled can't pass step 2, so the card alone never
 * records attendance: that's what stops a card being lent to someone else.
 *
 * Before step 2 the admin's gate schedule is checked ([GateAttendanceRepository.checkSchedule]):
 * scans alternate Coming In / Going Out, up to the set number per day.
 *
 * This view model outlives the screen (no back stack), so it ignores the
 * reader unless the screen is open - card taps on the Assign Card screen
 * must not record gate attendance.
 */
@HiltViewModel
class GateScanViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
    private val studentRepository: StudentRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val gateSyncManager: GateSyncManager,
    private val gateSyncScheduler: GateSyncScheduler,
    private val photoCache: StudentPhotoCache,
    private val rfidManager: RfidManager,
    networkMonitor: NetworkMonitor,
    deviceSettings: DeviceSettings,
) : ViewModel() {

    private val _state = MutableStateFlow<GateScanState>(GateScanState.Ready)
    val state: StateFlow<GateScanState> = _state.asStateFlow()

    private val _direction = MutableStateFlow(GateDirection.IN)
    val direction: StateFlow<GateDirection> = _direction.asStateFlow()

    private val _leaveDialog = MutableStateFlow<LeaveDialogState>(LeaveDialogState.Hidden)
    val leaveDialog: StateFlow<LeaveDialogState> = _leaveDialog.asStateFlow()

    /** Set when the screen may close; the screen navigates and calls [consumeLeave]. */
    private val _leaveApproved = MutableStateFlow(false)
    val leaveApproved: StateFlow<Boolean> = _leaveApproved.asStateFlow()

    private val _session = MutableStateFlow<List<SessionResult>>(emptyList())
    val session: StateFlow<List<SessionResult>> = _session.asStateFlow()

    val unsyncedCount: StateFlow<Int> = gateAttendanceRepository.observeUnsyncedAttendanceCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val isSyncing: StateFlow<Boolean> = gateSyncManager.isSyncing
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val readerStatus = rfidManager.status

    /** Coming In and Going Out scans per student per day; null until an admin sets it. */
    val scansPerDay: StateFlow<Int?> = deviceSettings.gateScansPerDay

    private var active = false
    private var autoReturnJob: Job? = null
    private var faceTimeoutJob: Job? = null

    init {
        rfidManager.register()
        viewModelScope.launch {
            rfidManager.events.filterIsInstance<RfidEvent.CardDetected>().collect { event -> onCard(event.uid) }
        }
    }

    /** Called each time the screen opens. */
    fun enter(direction: GateDirection) {
        active = true
        _direction.value = direction
        _state.value = GateScanState.Ready
        _leaveDialog.value = LeaveDialogState.Hidden
        _leaveApproved.value = false
        _session.value = emptyList()
    }

    /** Called when the screen closes, however it closes. */
    fun exit() {
        active = false
        cancelTimers()
        _state.value = GateScanState.Ready
    }

    suspend fun loadPhoto(student: StudentEntity): Bitmap? = photoCache.loadCachedBitmap(student.schoolId, student.studentId)

    private suspend fun onCard(rawUid: String) {
        if (!active || _leaveDialog.value != LeaveDialogState.Hidden) return
        // Mid face check: the card on the reader belongs to the person at the camera.
        if (_state.value is GateScanState.FaceCheck || _state.value is GateScanState.Verifying) return
        val uid = normalizeRfidUid(rawUid)
        if (uid.isEmpty()) return
        cancelTimers()

        val student = studentRepository.findByRfid(uid)
        if (student == null) {
            show(GateScanState.UnknownCard(uid), returnAfterMillis = 4_000)
            return
        }
        val dir = _direction.value
        val check = gateAttendanceRepository.checkSchedule(student.code, dir.apiValue)
        if (check !is GateScanCheck.Allowed) {
            notAllowed(student, dir, check)
            return
        }
        if (!faceTemplateRepository.hasTemplate(student.schoolId, student.studentId)) {
            rejected(student, uid, REASON_NOT_ENROLLED, score = null)
            show(GateScanState.NoFaceEnrolled(student), returnAfterMillis = 6_000)
            return
        }
        startFaceCheck(student, uid, attempt = 1)
    }

    private fun startFaceCheck(student: StudentEntity, uid: String, attempt: Int) {
        cancelTimers()
        _state.value = GateScanState.FaceCheck(student, uid, attempt)
        // The camera waits for a face indefinitely; a student who walks off
        // mustn't leave the gate stuck on their check.
        faceTimeoutJob = viewModelScope.launch {
            delay(FACE_TIMEOUT_MILLIS)
            val current = _state.value
            if (current is GateScanState.FaceCheck && current.attempt == attempt) {
                faceFailed(current.student, current.rfidUid, "No face confirmed within ${FACE_TIMEOUT_MILLIS / 1000} seconds", null, attempt)
            }
        }
    }

    /** From the existing auto-capture camera. */
    fun onFaceCaptured(bitmap: Bitmap) {
        val current = _state.value as? GateScanState.FaceCheck ?: return
        faceTimeoutJob?.cancel()
        val student = current.student
        _state.value = GateScanState.Verifying(student, current.rfidUid)
        viewModelScope.launch {
            when (val result = faceTemplateRepository.verify(student.schoolId, student.studentId, bitmap)) {
                is FaceVerificationResult.Matched -> confirmed(student, current.rfidUid, result.score)
                is FaceVerificationResult.NotMatched -> faceFailed(
                    student,
                    current.rfidUid,
                    "Face does not match ${student.name} (score ${"%.2f".format(Locale.US, result.score)})",
                    result.score,
                    current.attempt,
                )
                is FaceVerificationResult.NoFaceDetected ->
                    faceFailed(student, current.rfidUid, "No face detected in the photo", null, current.attempt)
                is FaceVerificationResult.NoTemplateEnrolled -> {
                    rejected(student, current.rfidUid, REASON_NOT_ENROLLED, null)
                    show(GateScanState.NoFaceEnrolled(student), returnAfterMillis = 6_000)
                }
            }
        }
    }

    /** Another attempt for the same card, same rules as the first. */
    fun tryAgain() {
        val current = _state.value as? GateScanState.FaceFailed ?: return
        startFaceCheck(current.student, current.rfidUid, current.attempt + 1)
    }

    /** Back to "tap your card". A check cancelled before any photo records nothing. */
    fun cancelCheck() {
        cancelTimers()
        _state.value = GateScanState.Ready
    }

    fun dismissResult() {
        cancelTimers()
        _state.value = GateScanState.Ready
    }

    private suspend fun confirmed(student: StudentEntity, uid: String, score: Float) {
        val dir = _direction.value
        when (val result = gateAttendanceRepository.recordConfirmed(student, uid, dir.apiValue, score)) {
            is GateRecordResult.Recorded -> {
                val detail = "${dir.label} ${result.number} of ${result.perDay} - RFID + face confirmed"
                log(student.name, result.scan.scanTime, success = true, detail = detail)
                show(GateScanState.Recorded(result.scan, result.number, result.perDay), returnAfterMillis = 3_000)
                uploadSoon()
            }
            is GateRecordResult.NotAllowed -> notAllowed(student, dir, result.check)
        }
    }

    /** Refused by the gate schedule: nothing saved, the attendant sees why. */
    private fun notAllowed(student: StudentEntity, dir: GateDirection, check: GateScanCheck) {
        val detail = when (check) {
            is GateScanCheck.SameAsLast -> "Not recorded - already ${dir.label}, next scan is ${dir.opposite.label}"
            is GateScanCheck.LimitReached -> "Not recorded - all ${check.perDay} ${dir.label} scans done today"
            GateScanCheck.NotSetUp -> "Not recorded - gate schedule not set up"
            is GateScanCheck.Allowed -> return
        }
        log(student.name, LocalTime.now().format(HH_MM), success = false, detail = detail)
        show(GateScanState.NotAllowed(student, dir, check), returnAfterMillis = 5_000)
    }

    private suspend fun faceFailed(student: StudentEntity, uid: String, reason: String, score: Float?, attempt: Int) {
        rejected(student, uid, reason, score)
        show(GateScanState.FaceFailed(student, uid, reason, attempt), returnAfterMillis = 20_000)
    }

    private suspend fun rejected(student: StudentEntity, uid: String, reason: String, score: Float?) {
        val scan = gateAttendanceRepository.recordRejected(student, uid, _direction.value.apiValue, reason, score)
        log(student.name, scan.scanTime, success = false, detail = reason)
        uploadSoon()
    }

    private fun log(name: String, time: String, success: Boolean, detail: String) {
        _session.value = (listOf(SessionResult(name, time, success, detail)) + _session.value).take(SESSION_LOG_SIZE)
    }

    /** Upload now if online; either way WorkManager uploads as soon as there's a connection. */
    private fun uploadSoon() {
        gateSyncScheduler.syncWhenOnline()
        viewModelScope.launch { gateSyncManager.flush() }
    }

    /** Shows [next] and goes back to "tap your card" on its own, so a queue of students keeps moving. */
    private fun show(next: GateScanState, returnAfterMillis: Long) {
        cancelTimers()
        _state.value = next
        autoReturnJob = viewModelScope.launch {
            delay(returnAfterMillis)
            if (_state.value == next) _state.value = GateScanState.Ready
        }
    }

    private fun cancelTimers() {
        autoReturnJob?.cancel()
        faceTimeoutJob?.cancel()
    }

    // ── Leaving the screen ──────────────────────────────────────────────

    /** Back arrow / system back. Asks first when attendance hasn't reached the server yet. */
    fun requestLeave() {
        cancelTimers()
        _state.value = GateScanState.Ready
        viewModelScope.launch {
            val unsynced = gateAttendanceRepository.unsyncedAttendanceCount()
            if (unsynced > 0) {
                _leaveDialog.value = LeaveDialogState.Prompt(unsynced)
            } else {
                _leaveApproved.value = true
            }
        }
    }

    /** "Save & Sync": records are already saved on the device - this uploads them, then leaves. */
    fun saveAndSync() {
        _leaveDialog.value = LeaveDialogState.Syncing
        viewModelScope.launch {
            val outcome = gateSyncManager.flush()
            val remaining = gateAttendanceRepository.unsyncedAttendanceCount()
            if (remaining == 0) {
                _leaveDialog.value = LeaveDialogState.Hidden
                _leaveApproved.value = true
                return@launch
            }
            gateSyncScheduler.syncWhenOnline()
            val reason = when {
                !isOnline.value -> "No internet connection."
                outcome is GateSyncOutcome.Finished && outcome.stoppedReason != null -> "${outcome.stoppedReason}."
                outcome is GateSyncOutcome.NotSignedIn -> "Not signed in."
                else -> "The server didn't accept them yet."
            }
            _leaveDialog.value = LeaveDialogState.NotFinished(
                "$reason $remaining record(s) are saved on this device as Pending Sync and will sync automatically " +
                    "when the connection returns.",
            )
        }
    }

    /** Records stay saved on the device as Pending Sync and upload automatically later. */
    fun leaveWithoutSyncing() {
        gateSyncScheduler.syncWhenOnline()
        _leaveDialog.value = LeaveDialogState.Hidden
        _leaveApproved.value = true
    }

    fun acknowledgeNotFinished() {
        _leaveDialog.value = LeaveDialogState.Hidden
        _leaveApproved.value = true
    }

    fun cancelLeave() {
        if (_leaveDialog.value is LeaveDialogState.Syncing) return
        _leaveDialog.value = LeaveDialogState.Hidden
    }

    fun consumeLeave() {
        _leaveApproved.value = false
    }

    companion object {
        private const val FACE_TIMEOUT_MILLIS = 30_000L
        private const val SESSION_LOG_SIZE = 6
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val REASON_NOT_ENROLLED = "No face enrolled for this student on this device"
    }
}
