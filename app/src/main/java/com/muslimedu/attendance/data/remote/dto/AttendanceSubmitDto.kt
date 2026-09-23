package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * One record in `/teacher_attendance_submit`'s `records` array - confirmed
 * against the real backend (AttendanceApi::teacher_attendance_submit()),
 * whose `$request->validate()` rules only ever look at these four keys. Any
 * other key sent (an earlier version of this DTO also sent verified_by_rfid,
 * verified_by_face, rfid_uid, face_match_score and idempotency_key) is
 * silently dropped by Laravel's validator rather than rejected or stored -
 * that richer verification data stays in [com.muslimedu.attendance.data.db.entities.AttendanceEntity]
 * locally, it's just never part of the wire payload, since the backend has
 * nowhere to put it.
 */
data class AttendanceRecordDto(
    @SerializedName("student_id") val studentId: Int,
    @SerializedName("status") val status: String,
    @SerializedName("check_in_time") val checkInTime: String?,
    @SerializedName("remarks") val remarks: String? = null,
)

/**
 * `source` is shared across the whole batch (top-level here), not per
 * record - confirmed from the same validation rules: `'source' =>
 * 'nullable|string|in:manual,qr,face'`.
 *
 * **Not used for individual RFID scans** - see
 * [com.muslimedu.attendance.data.remote.dto.AttendanceScanRequest]'s doc
 * comment for why. `AttendanceApi::teacher_attendance_submit()` locks the
 * whole (section_id, subject_id, date) roster the first time it's called
 * for that combination (`AttendanceService::lockRoster()`, called
 * automatically right after every successful submit); every later call for
 * the *same* combination - i.e. every next student's scan in the same
 * period - gets back HTTP 423 "This attendance has already been submitted
 * and locked" instead of saving anything. `SyncQueueManager` used to call
 * this endpoint once per scan and had no case for 423, so it fell into the
 * generic retry-then-give-up branch: the first student of each class period
 * synced, and every student after them silently failed after exhausting
 * retries, with nothing surfaced beyond the Admin Dashboard's failed-sync
 * count. This is what `/teacher_attendance_scan` (see [AttendanceScanRequest])
 * exists to avoid - it's kept here only for a future "finalize the whole
 * day's roster in one deliberate action" feature (the still-unbuilt
 * AttendanceConfirmScreen in the roadmap), which is the one case where
 * locking on submit is actually the desired behavior.
 */
data class AttendanceSubmitRequest(
    @SerializedName("section_id") val sectionId: Int,
    @SerializedName("subject_id") val subjectId: Int,
    @SerializedName("date") val date: String,
    @SerializedName("source") val source: String,
    @SerializedName("records") val records: List<AttendanceRecordDto>,
)

/**
 * Confirmed against the real response shape: `{message, summary, count,
 * locked, locked_at}`. There is no `submitted`/`skipped`/`attendance_ids` -
 * an earlier version of this DTO invented all three, so `serverAttendanceId`
 * was always being written as null regardless of what the server actually
 * did. `message` isn't declared here; [com.muslimedu.attendance.data.remote.dto.ApiEnvelope.message]
 * already carries it since the whole top-level object (this one included) is
 * this response's payload - there's no separate `data` wrapper.
 */
data class AttendanceSubmitData(
    @SerializedName("summary") val summary: Map<String, Int>?,
    @SerializedName("count") val count: Int?,
    @SerializedName("locked") val locked: Boolean = false,
    @SerializedName("locked_at") val lockedAt: String?,
)
