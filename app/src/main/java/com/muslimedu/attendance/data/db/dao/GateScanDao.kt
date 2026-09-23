package com.muslimedu.attendance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GateScanDao {

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

    @Query(
        "SELECT * FROM gate_scans WHERE school_id = :schoolId AND student_code = :code " +
            "ORDER BY scanned_at DESC LIMIT 1",
    )
    suspend fun latestForCode(schoolId: Int, code: String): GateScanEntity?

    @Query("SELECT COUNT(*) FROM gate_scans WHERE school_id = :schoolId AND sync_status = :syncStatus")
    fun observeCount(schoolId: Int, syncStatus: String): Flow<Int>

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
