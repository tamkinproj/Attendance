package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * One entry from `/teacher_attendance_classes` - confirmed against the real
 * backend (AttendanceApi::teacher_attendance_classes()), which returns a flat
 * list under `classes`, not a `sections` list with a nested `subject` object
 * as an earlier version of this DTO guessed. `subjectId` is never missing:
 * a homeroom entry carries the school's homeroom sentinel id (0) rather than
 * omitting the field, so there is no "this class has no subject" case to
 * handle - every entry is directly usable.
 */
data class TeacherClassDto(
    @SerializedName("section_id") val sectionId: Int,
    @SerializedName("section_name") val sectionName: String,
    @SerializedName("class_id") val classId: Int?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("subject_id") val subjectId: Int,
    @SerializedName("subject_name") val subjectName: String?,
    /** "homeroom" or "subject" - cosmetic only, nothing currently branches on it. */
    @SerializedName("role") val role: String?,
)

data class TeacherClassesData(
    @SerializedName("classes") val classes: List<TeacherClassDto>?,
)

data class RosterRequest(
    @SerializedName("section_id") val sectionId: Int,
    @SerializedName("subject_id") val subjectId: Int,
    @SerializedName("date") val date: String,
)

/**
 * One student row from `/teacher_attendance_roster` - confirmed against the
 * real backend (AttendanceApi::teacher_attendance_roster()). There is no
 * `rfid_card_number` field here or anywhere else on the backend: it has no
 * concept of an RFID card at all (only `code`, the same identifier a QR scan
 * resolves via `/teacher_attendance_scan`). RFID card-to-student assignment
 * is necessarily local-only - see [com.muslimedu.attendance.data.repository.StudentRepository.assignRfidCard]
 * and, for how a re-sync avoids erasing that local assignment, this file's
 * doc comment on [RosterData].
 */
data class RosterStudentDto(
    @SerializedName("student_id") val studentId: Int,
    @SerializedName("student_name") val studentName: String,
    @SerializedName("code") val code: String,
    @SerializedName("photo") val photo: String?,
    @SerializedName("address") val address: String?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("age") val age: Int?,
    // The rest describe *today's already-recorded* attendance for this
    // student, if any - this app tracks its own attendance locally
    // (AttendanceEntity) rather than mirroring these back into the roster
    // cache, so they're read only where a specific need shows up (currently
    // none), not stored.
    @SerializedName("status") val status: String?,
    @SerializedName("check_in_time") val checkInTime: String?,
    @SerializedName("remarks") val remarks: String?,
    @SerializedName("attendance_id") val attendanceId: Int?,
)

/**
 * `/teacher_attendance_roster`'s response, confirmed against the real
 * backend. `section_id`/`section_name` are top-level fields here, not a
 * nested `section` object - an earlier version of this DTO guessed the
 * latter (matching neither shape, since the classes endpoint doesn't nest
 * one either).
 *
 * IMPORTANT for [com.muslimedu.attendance.data.repository.RosterRepository.syncRoster]:
 * since [RosterStudentDto] carries no RFID field, upserting a returned row
 * over an existing local [com.muslimedu.attendance.data.db.entities.StudentEntity]
 * must carry the existing row's `rfidCardNumber` (and `id`, so this becomes an
 * update rather than a delete-and-reinsert against the `code` unique index)
 * forward rather than blindly overwriting it with null on every re-sync -
 * otherwise a card assignment gets silently wiped out the next time the
 * teacher's roster syncs.
 */
data class RosterData(
    @SerializedName("section_id") val sectionId: Int?,
    @SerializedName("section_name") val sectionName: String?,
    @SerializedName("subject_id") val subjectId: Int?,
    @SerializedName("date") val date: String?,
    // Nullable for the same reason as LoginData.user: Gson leaves an absent
    // field null whatever Kotlin declares, and a missing roster should be a
    // handled error rather than an NPE deep in the sync.
    @SerializedName("students") val students: List<RosterStudentDto>?,
    @SerializedName("summary") val summary: Map<String, Int>?,
    @SerializedName("locked") val locked: Boolean = false,
    @SerializedName("locked_at") val lockedAt: String?,
    @SerializedName("locked_by_name") val lockedByName: String?,
)
