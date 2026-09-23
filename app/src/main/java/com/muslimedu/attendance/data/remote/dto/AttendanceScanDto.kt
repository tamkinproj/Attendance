package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * `/teacher_attendance_scan` - confirmed against the real backend
 * (`AttendanceApi::teacher_attendance_scan()`). This is the "one call per
 * scan" endpoint the RFID flow actually needs, unlike `/teacher_attendance_submit`
 * (see [AttendanceSubmitRequest]'s doc comment): it calls
 * `AttendanceService::markAttendance()` directly with no lock check at all,
 * so any number of separate calls for the same section/subject/date each
 * succeed independently - exactly what a class of students trickling in one
 * RFID tap at a time needs.
 *
 * Trade-offs versus `/teacher_attendance_submit`, both confirmed from the
 * controller source rather than assumed:
 * - The student is resolved server-side by `code`, not `student_id` - the
 *   validation rules only declare `section_id`, `subject_id`, `date`, `code`.
 * - `status` is always `'present'` and `check_in_time` is always the
 *   server's `now()` at the moment this call is received - there is no way
 *   to pass either explicitly. A scan recorded while offline and synced
 *   later therefore gets the *sync* time on the backend, not the actual
 *   scan time; [com.muslimedu.attendance.data.db.entities.AttendanceEntity.checkInTime]
 *   still keeps the real scan time locally, this only affects what the web
 *   dashboard shows for a delayed sync.
 * - `source` is hardcoded to `'qr'` server-side - there is no `source` field
 *   in this endpoint's validation rules to override it, unlike
 *   `/teacher_attendance_submit`'s. Every RFID-triggered scan is therefore
 *   recorded on the backend as if it were a QR scan; the locally-stored
 *   [com.muslimedu.attendance.data.db.entities.AttendanceEntity.verifiedByRfid]/
 *   [com.muslimedu.attendance.data.db.entities.AttendanceEntity.verifiedByFace]
 *   columns still record the real method, this only affects the backend's
 *   own `source` column.
 */
data class AttendanceScanRequest(
    @SerializedName("section_id") val sectionId: Int,
    @SerializedName("subject_id") val subjectId: Int,
    @SerializedName("date") val date: String,
    @SerializedName("code") val code: String,
)

/** The one resolved student this scan matched, per the real response shape. */
data class AttendanceScanStudentDto(
    @SerializedName("student_id") val studentId: Int,
    @SerializedName("student_name") val studentName: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("check_in_time") val checkInTime: String?,
    @SerializedName("attendance_id") val attendanceId: Int?,
)

/**
 * No `data` wrapper key on the real response either (`{message, student,
 * summary}` all at the top level) - the tolerant top-level fallback in
 * [com.muslimedu.attendance.data.remote.ApiEnvelopeTypeAdapterFactory]
 * handles that the same way it does for every other endpoint here.
 */
data class AttendanceScanData(
    @SerializedName("student") val student: AttendanceScanStudentDto?,
)
