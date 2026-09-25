package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Admin-only, campus-wide gate in/out attendance -
 * `/admin_gate_attendance_scan` and `/admin_gate_attendance_today`. New
 * backend endpoints, not just new app screens - see this app's CLAUDE.md
 * "Gate In/Out Attendance" entry for why: every existing attendance
 * endpoint (`teacher_attendance_*`) gates on `requireTeacher()`, which
 * rejects an admin account outright regardless of target, and there was no
 * gate/check-in/check-out concept anywhere in the API before this.
 *
 * Resolves the student by `code` across the WHOLE school (any active
 * student, not scoped to one section's enrollment) - genuinely campus-wide,
 * unlike [AttendanceScanRequest] which only matches a student already
 * enrolled in the caller's specific section. Storage-side, a gate scan is a
 * normal `attendances` row under a dedicated sentinel `subject_id`, so it
 * never collides with, or overwrites, that student's real class/homeroom
 * attendance for the same day - both exist as separate rows.
 */
data class GateAttendanceScanRequest(
    @SerializedName("code") val code: String,
    @SerializedName("direction") val direction: String, // "in" or "out"
    @SerializedName("date") val date: String? = null,
    /**
     * "HH:mm", the real moment of an offline scan. Read by the backend patch
     * (see CLAUDE.md "Backend changes"); a server without it ignores the
     * field and stamps its own clock at upload time.
     */
    @SerializedName("time") val time: String? = null,
    /** The card that identified the student - the server keeps it with the event. */
    @SerializedName("rfid_uid") val rfidUid: String? = null,
    @SerializedName("rfid_verified") val rfidVerified: Boolean? = null,
    /** Always true from this app: only face-confirmed records are sent here (failed ones go to [GateRejectedScanRequest]). */
    @SerializedName("face_confirmed") val faceConfirmed: Boolean? = null,
    @SerializedName("face_score") val faceScore: Float? = null,
    /** Per-record UUID: the server ignores an event id it already has, so a retried upload is never counted twice. */
    @SerializedName("device_event_id") val deviceEventId: String? = null,
)

/**
 * `/admin_gate_rejected_scan`: a card read whose face check failed. The
 * server only logs it (for the web admin's face-status view) - it is never
 * attendance. A separate endpoint on purpose: a server that doesn't know
 * about face results can't mistake one of these for a check-in.
 */
data class GateRejectedScanRequest(
    @SerializedName("code") val code: String,
    @SerializedName("direction") val direction: String,
    @SerializedName("date") val date: String,
    @SerializedName("time") val time: String,
    @SerializedName("rfid_uid") val rfidUid: String?,
    @SerializedName("reason") val reason: String?,
    @SerializedName("face_score") val faceScore: Float?,
    @SerializedName("device_event_id") val deviceEventId: String,
)

data class GateRejectedScanData(
    @SerializedName("event_id") val eventId: Int?,
)

/**
 * `/admin_student_rfid_set`: the server's card registry. [action] "assign"
 * makes [rfidUid] the student's one active card (any previous card is
 * deactivated - a replacement); "remove" deactivates their card. Both are
 * idempotent, so a retried upload is harmless.
 */
data class StudentRfidSetRequest(
    @SerializedName("code") val code: String,
    @SerializedName("action") val action: String,
    @SerializedName("rfid_uid") val rfidUid: String? = null,
) {
    companion object {
        const val ACTION_ASSIGN = "assign"
        const val ACTION_REMOVE = "remove"
    }
}

data class StudentRfidSetData(
    @SerializedName("student_id") val studentId: Int?,
    @SerializedName("code") val code: String?,
    @SerializedName("rfid_uid") val rfidUid: String?,
)

/** `admin_gate_students` - added by the backend patch, see CLAUDE.md "Backend changes". */
class GateStudentsRequest

data class GateStudentDto(
    @SerializedName("student_id") val studentId: Int,
    @SerializedName(value = "name", alternate = ["student_name"]) val name: String?,
    @SerializedName("code") val code: String?,
    @SerializedName("photo") val photo: String?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("section_id") val sectionId: Int?,
    @SerializedName("section_name") val sectionName: String?,
    /** The student's active card on the server, or null for none. Only meaningful when [GateStudentsData.rfidManaged]. */
    @SerializedName("rfid_uid") val rfidUid: String? = null,
)

data class GateStudentsData(
    @SerializedName("students") val students: List<GateStudentDto>?,
    /**
     * True when the server keeps the card registry. Only then does a
     * download update cards on this device - an older server that just
     * doesn't send `rfid_uid` must not wipe every card here.
     */
    @SerializedName("rfid_managed") val rfidManaged: Boolean? = null,
)

data class GateAttendanceScanStudentDto(
    @SerializedName("student_id") val studentId: Int,
    @SerializedName("student_name") val studentName: String?,
    @SerializedName("photo") val photo: String?,
    @SerializedName("check_in_time") val checkInTime: String?,
    @SerializedName("last_direction") val lastDirection: String?,
    @SerializedName("last_time") val lastTime: String?,
    @SerializedName("attendance_id") val attendanceId: Int?,
)

/** No `data` wrapper key on the real response - same tolerant top-level fallback as [AttendanceScanData]. */
data class GateAttendanceScanData(
    @SerializedName("student") val student: GateAttendanceScanStudentDto?,
)

data class GateAttendanceTodayRequest(
    @SerializedName("date") val date: String? = null,
)

/** One student's gate activity for the requested day - the "who's on/off campus" list. */
data class GateAttendanceRecordDto(
    @SerializedName("attendance_id") val attendanceId: Int,
    @SerializedName("student_id") val studentId: Int,
    @SerializedName("student_name") val studentName: String?,
    @SerializedName("code") val code: String?,
    @SerializedName("photo") val photo: String?,
    @SerializedName("check_in_time") val checkInTime: String?,
    @SerializedName("last_direction") val lastDirection: String?,
    @SerializedName("last_time") val lastTime: String?,
)

data class GateAttendanceTodayData(
    @SerializedName("date") val date: String?,
    @SerializedName("students") val students: List<GateAttendanceRecordDto>?,
)
