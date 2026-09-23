package com.muslimedu.attendance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.AttendanceEntity

@Dao
interface AttendanceDao {

    @Insert
    suspend fun insert(attendance: AttendanceEntity): Long

    @Query(
        "SELECT * FROM attendance WHERE school_id = :schoolId AND student_id = :studentId " +
            "AND scan_date = :scanDate LIMIT 1",
    )
    suspend fun findForStudentOnDate(schoolId: Int, studentId: Int, scanDate: String): AttendanceEntity?

    @Query(
        "SELECT * FROM attendance WHERE sync_status = 'pending' " +
            "AND (next_retry_at IS NULL OR next_retry_at <= :now) ORDER BY created_at ASC",
    )
    suspend fun getDueForSync(now: Long): List<AttendanceEntity>

    @Query(
        "UPDATE attendance SET sync_status = :syncStatus, sync_attempts = :syncAttempts, " +
            "next_retry_at = :nextRetryAt, last_sync_at = :lastSyncAt, " +
            "server_attendance_id = :serverAttendanceId, error_message = :errorMessage, " +
            "updated_at = :lastSyncAt WHERE id = :id",
    )
    suspend fun updateSyncResult(
        id: Long,
        syncStatus: String,
        syncAttempts: Int,
        nextRetryAt: Long?,
        lastSyncAt: Long,
        serverAttendanceId: Int?,
        errorMessage: String?,
    )

    // Every dashboard read below is scoped to one school_id: the local DB
    // outlives a logout, so a device that has had two accounts on it holds
    // both schools' rows, and an unscoped count reports the previous
    // account's numbers to whoever logs in next.
    @Query("SELECT COUNT(*) FROM attendance WHERE school_id = :schoolId AND scan_date = :scanDate AND sync_status = :syncStatus")
    suspend fun countByStatusOnDate(schoolId: Int, scanDate: String, syncStatus: String): Int

    /** Every row recorded today regardless of sync state - status is always 'present' today (see AttendanceEntity), so this is the Teacher Dashboard's real "Present" count. */
    @Query("SELECT COUNT(*) FROM attendance WHERE school_id = :schoolId AND scan_date = :scanDate")
    suspend fun countRecordedOnDate(schoolId: Int, scanDate: String): Int

    /** Per-record detail behind a "Failed" count - SyncStatusScreen shows these instead of just the distinct messages [getFailedMessagesOnDate] returns. */
    @Query(
        "SELECT * FROM attendance WHERE school_id = :schoolId AND scan_date = :scanDate " +
            "AND sync_status = 'failed' ORDER BY created_at DESC",
    )
    suspend fun getFailedOnDate(schoolId: Int, scanDate: String): List<AttendanceEntity>

    /** The actual reason behind each failed row today - see AdminDashboardScreen, which shows these instead of just a count. */
    @Query(
        "SELECT DISTINCT error_message FROM attendance WHERE school_id = :schoolId AND scan_date = :scanDate " +
            "AND sync_status = 'failed' AND error_message IS NOT NULL",
    )
    suspend fun getFailedMessagesOnDate(schoolId: Int, scanDate: String): List<String>

    @Query("SELECT * FROM attendance ORDER BY scan_date DESC, created_at DESC")
    suspend fun getAll(): List<AttendanceEntity>

    /** Gives up-front failures another chance - [SyncQueueManager] still applies backoff from here. */
    @Query("UPDATE attendance SET sync_status = 'pending', sync_attempts = 0, next_retry_at = NULL WHERE sync_status = 'failed'")
    suspend fun resetFailedToPending()

    /**
     * One-time repair for rows recorded against the sample/demo roster
     * before a bug was found in `AttendanceRepository.recordScan()`: it used
     * to prefer the current session's real `school_id` over the scanned
     * student's own - correct for a real roster-synced student (whose
     * `school_id` already matched the session's at sync time), but wrong for
     * the demo seed data, which is hardcoded to `school_id = 1` regardless of
     * which real account is logged in. That mismatch meant
     * `findBySchoolAndStudentId(record.schoolId, record.studentId)` - keyed
     * on the real `school_id` - could never find the demo student row (still
     * filed at `school_id = 1`), so `SyncQueueManager` gave up permanently
     * with "Student no longer in local cache" even though the row was right
     * there the whole time.
     *
     * Keyed on the three sample students' own hardcoded `rfid_uid` values,
     * not `student_id` alone - real RFID hardware would essentially never
     * produce these exact synthetic strings, whereas a real school's first
     * enrolled students could plausibly have server `student_id` 1/2/3, and
     * this must never touch their real attendance data. Safe to run on every
     * app start: a no-op once these rows are already corrected.
     */
    @Query(
        "UPDATE attendance SET school_id = 1, sync_status = 'pending', sync_attempts = 0, " +
            "next_retry_at = NULL, error_message = NULL " +
            "WHERE rfid_uid IN ('04:1A:2B:3C', '04:5D:6E:7F', '09:AA:BB:CC') AND school_id != 1",
    )
    suspend fun repairDemoAttendanceSchoolId()
}
