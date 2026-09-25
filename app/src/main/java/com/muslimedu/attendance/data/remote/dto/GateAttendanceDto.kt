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
)

data class GateStudentsData(
    @SerializedName("students") val students: List<GateStudentDto>?,
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
