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
     * "HH:mm", the real moment of an offline scan. NOT yet accepted by the
     * backend - it currently stamps the server's clock at upload time. This
     * is the proposed contract (see CLAUDE.md "Offline gate-only mode");
     * Laravel ignores unknown fields, so sending it early is harmless and
     * times become correct the moment the backend reads it.
     */
    @SerializedName("time") val time: String? = null,
)

/** Proposed `admin_gate_students` - not live on the backend yet, see CLAUDE.md "Offline gate-only mode". */
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
