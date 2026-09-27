package com.muslimedu.attendance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import kotlinx.coroutines.flow.Flow

/** What the device-health report says about this gate's records (times are epoch millis). */
data class GateScanHealth(
    val pending: Int,
    val failed: Int,
    val oldestPendingAt: Long?,
    val lastScanAt: Long?,
    val lastUploadAt: Long?,
    val recordedToday: Int,
    val rejectedToday: Int,
)

@Dao
interface GateScanDao {

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND outcome = 'recorded' AND sync_status = 'pending') AS pending, " +
            "(SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND sync_status = 'failed') AS failed, " +
            "(SELECT MIN(scanned_at) FROM gate_scans WHERE school_id = :schoolId AND outcome = 'recorded' AND sync_status = 'pending') AS oldestPendingAt, " +
            "(SELECT MAX(scanned_at) FROM gate_scans WHERE school_id = :schoolId) AS lastScanAt, " +
            "(SELECT MAX(last_sync_at) FROM gate_scans WHERE school_id = :schoolId AND sync_status = 'synced') AS lastUploadAt, " +
            "(SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND scan_date = :today AND outcome = 'recorded') AS recordedToday, " +
            "(SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND scan_date = :today AND outcome = 'rejected') AS rejectedToday",
    )
    suspend fun health(schoolId: Int, today: String): GateScanHealth

    @Insert
    suspend fun insert(scan: GateScanEntity): Long

    /**
     * Every pending scan, oldest first. Deliberately not filtered by
     * `next_retry_at`: the uploader walks this in order and stops at the
     * first scan still backing off, so a later "out" can never reach the
     * server before the "in" it follows.
     */
    @Query("SELECT * FROM gate_scans WHERE school_id = :schoolId AND sync_status = 'pending' ORDER BY scanned_at ASC")
    suspend fun getPending(schoolId: Int): List<GateScanEntity>

    @Query(
        "UPDATE gate_scans SET sync_status = :syncStatus, sync_attempts = :syncAttempts, " +
            "next_retry_at = :nextRetryAt, last_sync_at = :lastSyncAt, " +
            "server_attendance_id = :serverAttendanceId, error_message = :errorMessage WHERE id = :id",
    )
    suspend fun updateSyncResult(
        id: Long,
        syncStatus: String,
        syncAttempts: Int,
        nextRetryAt: Long?,
        lastSyncAt: Long?,
        serverAttendanceId: Int?,
        errorMessage: String?,
    )

    @Query("SELECT * FROM gate_scans WHERE school_id = :schoolId AND scan_date = :scanDate ORDER BY scanned_at DESC")
    fun observeForDate(schoolId: Int, scanDate: String): Flow<List<GateScanEntity>>

    /** Newest first, every outcome - the dashboard's recent list and the history screen. */
    @Query("SELECT * FROM gate_scans WHERE school_id = :schoolId ORDER BY scanned_at DESC LIMIT :limit")
    fun observeRecent(schoolId: Int, limit: Int): Flow<List<GateScanEntity>>

    /** Dates that have any gate record, newest first - the history screen's day picker. */
    @Query("SELECT DISTINCT scan_date FROM gate_scans WHERE school_id = :schoolId ORDER BY scan_date DESC")
    fun observeDates(schoolId: Int): Flow<List<String>>

    /** The student's *attendance* (face-confirmed) records on [scanDate] - what the gate schedule counts. */
    @Query(
        "SELECT * FROM gate_scans WHERE school_id = :schoolId AND student_code = :code AND scan_date = :scanDate " +
            "AND outcome = 'recorded' ORDER BY scanned_at ASC",
    )
    suspend fun recordedForCodeOnDate(schoolId: Int, code: String, scanDate: String): List<GateScanEntity>

    @Query("SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND sync_status = :syncStatus")
    fun observeCount(schoolId: Int, syncStatus: String): Flow<Int>

    /** Attendance records not yet on the server - what "unsynced records" means to the gate attendant. */
    @Query(
        "SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND outcome = 'recorded' AND sync_status = 'pending'",
    )
    fun observeUnsyncedAttendanceCount(schoolId: Int): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND outcome = 'recorded' AND sync_status = 'pending'",
    )
    suspend fun unsyncedAttendanceCount(schoolId: Int): Int

    @Query("SELECT MAX(last_sync_at) FROM gate_scans WHERE school_id = :schoolId AND sync_status = 'synced'")
    fun observeLastSyncedAt(schoolId: Int): Flow<Long?>

    @Query("SELECT * FROM gate_scans WHERE school_id = :schoolId AND sync_status = 'failed' ORDER BY scanned_at DESC")
    fun observeFailed(schoolId: Int): Flow<List<GateScanEntity>>

    @Query(
        "UPDATE gate_scans SET sync_status = 'pending', sync_attempts = 0, next_retry_at = NULL " +
            "WHERE school_id = :schoolId AND sync_status = 'failed'",
    )
    suspend fun retryFailed(schoolId: Int)

    @Query("UPDATE gate_scans SET school_id = :toSchoolId WHERE school_id = :fromSchoolId")
    suspend fun moveToSchool(fromSchoolId: Int, toSchoolId: Int)
}
