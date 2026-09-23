package com.muslimedu.attendance.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single scan recorded locally, synced to `/teacher_attendance_submit`.
 *
 * The spec's schema splits this into an `attendance` table and a separate
 * generic `sync_queue` table, but `attendance` already carries its own
 * sync_status/sync_attempts/idempotency_key/last_sync_at/error_message
 * columns - a second table would just be a duplicate source of truth for the
 * one action type ("submit this scan") that actually exists right now. This
 * folds the two together and adds [nextRetryAt], which the spec's sync_queue
 * table has but attendance doesn't, since SyncQueueManager needs it for
 * backoff scheduling either way. Revisit as a real separate queue if a second
 * action type (face enrollment sync, etc.) ever needs one.
 */
@Entity(
    tableName = "attendance",
    indices = [
        Index(value = ["scan_date"]),
        Index(value = ["sync_status"]),
        Index(value = ["school_id", "student_id"]),
        Index(value = ["school_id", "student_id", "scan_date"]),
        Index(value = ["idempotency_key"], unique = true),
    ],
)
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "school_id") val schoolId: Int,
    @ColumnInfo(name = "section_id") val sectionId: Int,
    @ColumnInfo(name = "subject_id") val subjectId: Int,
    @ColumnInfo(name = "student_id") val studentId: Int,
    @ColumnInfo(name = "scan_date") val scanDate: String,
    val status: String = "present",
    @ColumnInfo(name = "check_in_time") val checkInTime: String,
    @ColumnInfo(name = "rfid_uid") val rfidUid: String?,
    @ColumnInfo(name = "verified_by_rfid") val verifiedByRfid: Boolean = false,
    @ColumnInfo(name = "verified_by_face") val verifiedByFace: Boolean = false,
    @ColumnInfo(name = "face_match_score") val faceMatchScore: Float? = null,
    @ColumnInfo(name = "manual_override") val manualOverride: Boolean = false,
    @ColumnInfo(name = "override_by") val overrideBy: String? = null,
    @ColumnInfo(name = "sync_status") val syncStatus: String = "pending",
    @ColumnInfo(name = "sync_attempts") val syncAttempts: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "server_attendance_id") val serverAttendanceId: Int? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SYNC_PENDING = "pending"
        const val SYNC_SYNCED = "synced"
        const val SYNC_FAILED = "failed"
    }
}
