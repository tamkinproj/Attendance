package com.muslimedu.attendance.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One gate in/out attempt, recorded on-device first and uploaded later by
 * [com.muslimedu.attendance.sync.GateSyncManager].
 *
 * Every row starts from a real RFID read ([rfidUid], [rfidVerified]) that
 * identified a student on this device. [outcome] says what the face check
 * made of it: [OUTCOME_RECORDED] (face confirmed - this is attendance,
 * uploaded to `/admin_gate_attendance_scan`) or [OUTCOME_REJECTED] (face not
 * confirmed or not enrolled - NOT attendance, kept for the history and
 * uploaded to `/admin_gate_rejected_scan` so the web admin can see card
 * sharing attempts). [eventId] is a per-row UUID the server uses to ignore
 * an upload it has already stored.
 *
 * Keyed to the student by [studentCode] only - the backend resolves gate
 * scans by `code` across the whole school, so no server student id is
 * needed here, and a student added by hand on this device (with their real
 * school code) syncs exactly like a downloaded one. [studentName] is a
 * display snapshot, not an identity.
 *
 * [scanDate]/[scanTime] are the real moment of the scan, sent with the
 * upload so a scan made offline at 07:30 and synced at 15:00 is still
 * recorded as 07:30 (needs the backend's `time` field - see CLAUDE.md
 * "Offline gate-only mode").
 *
 * Any schema change here must be mirrored in AppDatabase's migration SQL.
 */
@Entity(
    tableName = "gate_scans",
    indices = [
        Index(value = ["sync_status"]),
        Index(value = ["school_id", "scan_date"]),
    ],
)
data class GateScanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "school_id") val schoolId: Int,
    @ColumnInfo(name = "student_code") val studentCode: String,
    @ColumnInfo(name = "student_name") val studentName: String?,
    val direction: String,
    @ColumnInfo(name = "scan_date") val scanDate: String,
    @ColumnInfo(name = "scan_time") val scanTime: String,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long,
    @ColumnInfo(name = "verified_by_face") val verifiedByFace: Boolean,
    @ColumnInfo(name = "face_match_score") val faceMatchScore: Float?,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SYNC_PENDING,
    @ColumnInfo(name = "sync_attempts") val syncAttempts: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "server_attendance_id") val serverAttendanceId: Int? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    // v8 columns. No defaultValue here on purpose: the migration's SQL
    // DEFAULTs back-fill old rows, and Room only compares defaults the
    // entity declares.
    /** The student's server id (negative for one added by hand on this device). Display/code is [studentCode]. */
    @ColumnInfo(name = "student_id") val studentId: Int? = null,
    @ColumnInfo(name = "section_name") val sectionName: String? = null,
    @ColumnInfo(name = "rfid_uid") val rfidUid: String? = null,
    @ColumnInfo(name = "rfid_verified") val rfidVerified: Boolean = false,
    val outcome: String = OUTCOME_RECORDED,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    @ColumnInfo(name = "event_id") val eventId: String = "",
    // v11: the late check on a Coming In. [lateAfter] ("HH:mm") is the time
    // it was checked against - null when that Coming In has no late check,
    // so "on time" (late false, lateAfter set) differs from "not checked".
    @ColumnInfo(name = "is_late") val late: Boolean = false,
    @ColumnInfo(name = "minutes_late") val minutesLate: Int? = null,
    @ColumnInfo(name = "late_after") val lateAfter: String? = null,
) {
    /** Attendance only when both checks passed - what a teacher can sync into class attendance. */
    val isVerifiedAttendance: Boolean get() = outcome == OUTCOME_RECORDED && rfidVerified && verifiedByFace

    companion object {
        const val SYNC_PENDING = "pending"
        const val SYNC_SYNCED = "synced"
        const val SYNC_FAILED = "failed"
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"
        const val OUTCOME_RECORDED = "recorded"
        const val OUTCOME_REJECTED = "rejected"
    }
}
