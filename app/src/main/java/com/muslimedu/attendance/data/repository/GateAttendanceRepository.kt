package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class GateRecordResult {
    data class Recorded(val scan: GateScanEntity) : GateRecordResult()

    /** Same student, same direction, moments ago - a double tap, not a new event. */
    data class Duplicate(val existing: GateScanEntity) : GateRecordResult()
}

/**
 * Offline-first gate in/out attendance: every attempt is written to the local
 * `gate_scans` table immediately, with no network needed, and uploaded later
 * by [com.muslimedu.attendance.sync.GateSyncManager]. The dashboard and
 * history read the same table, so they work offline too and show this
 * device's own records (other gates' are on the web admin).
 *
 * Only [recordConfirmed] creates attendance, and only after the card was
 * read AND the existing face check matched. [recordRejected] keeps a failed
 * attempt for the history - it is never attendance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GateAttendanceRepository @Inject constructor(
    private val gateScanDao: GateScanDao,
    private val deviceSettings: DeviceSettings,
) {
    /**
     * The student's attendance record for [direction] from the last
     * [DUPLICATE_WINDOW_MILLIS], if any - a card held on the reader or tapped
     * twice. Checked before the face step so a double tap doesn't make the
     * student look at the camera again.
     */
    suspend fun recentDuplicate(code: String, direction: String, nowMillis: Long = System.currentTimeMillis()): GateScanEntity? {
        val latest = gateScanDao.latestRecordedForCode(deviceSettings.schoolId.value, code) ?: return null
        return latest.takeIf { it.direction == direction && nowMillis - it.scannedAt < DUPLICATE_WINDOW_MILLIS }
    }

    /** Card read + face confirmed: this is the attendance record. */
    suspend fun recordConfirmed(
        student: StudentEntity,
        rfidUid: String,
        direction: String,
        faceMatchScore: Float?,
        now: LocalDateTime = LocalDateTime.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): GateRecordResult {
        recentDuplicate(student.code, direction, nowMillis)?.let { return GateRecordResult.Duplicate(it) }
        val scan = newScan(student, rfidUid, direction, now, nowMillis).copy(
            verifiedByFace = true,
            faceMatchScore = faceMatchScore,
            outcome = GateScanEntity.OUTCOME_RECORDED,
        )
        return GateRecordResult.Recorded(scan.copy(id = gateScanDao.insert(scan)))
    }

    /** Card read, face NOT confirmed (no match, no face, not enrolled): kept for the history, not attendance. */
    suspend fun recordRejected(
        student: StudentEntity,
        rfidUid: String,
        direction: String,
        reason: String,
        faceMatchScore: Float?,
        now: LocalDateTime = LocalDateTime.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): GateScanEntity {
        val scan = newScan(student, rfidUid, direction, now, nowMillis).copy(
            verifiedByFace = false,
            faceMatchScore = faceMatchScore,
            outcome = GateScanEntity.OUTCOME_REJECTED,
            failureReason = reason,
        )
        return scan.copy(id = gateScanDao.insert(scan))
    }

    private fun newScan(
        student: StudentEntity,
        rfidUid: String,
        direction: String,
        now: LocalDateTime,
        nowMillis: Long,
    ) = GateScanEntity(
        schoolId = deviceSettings.schoolId.value,
        studentCode = student.code,
        studentName = student.name,
        direction = direction,
        scanDate = now.toLocalDate().toString(),
        scanTime = now.format(TIME_FORMAT),
        scannedAt = nowMillis,
        verifiedByFace = false,
        faceMatchScore = null,
        studentId = student.studentId,
        sectionName = student.sectionName,
        rfidUid = rfidUid,
        rfidVerified = true,
        eventId = UUID.randomUUID().toString(),
    )

    fun observeToday(): Flow<List<GateScanEntity>> =
        deviceSettings.schoolId.flatMapLatest { schoolId ->
            gateScanDao.observeForDate(schoolId, LocalDate.now().toString())
        }

    fun observeForDate(date: String): Flow<List<GateScanEntity>> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeForDate(schoolId, date) }

    fun observeRecent(limit: Int): Flow<List<GateScanEntity>> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeRecent(schoolId, limit) }

    fun observeDates(): Flow<List<String>> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeDates(schoolId) }

    fun observeCount(syncStatus: String): Flow<Int> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeCount(schoolId, syncStatus) }

    /** Face-confirmed attendance not yet uploaded - failed attempts waiting to upload don't count. */
    fun observeUnsyncedAttendanceCount(): Flow<Int> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeUnsyncedAttendanceCount(schoolId) }

    suspend fun unsyncedAttendanceCount(): Int = gateScanDao.unsyncedAttendanceCount(deviceSettings.schoolId.value)

    fun observeLastSyncedAt(): Flow<Long?> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeLastSyncedAt(schoolId) }

    fun observeFailed(): Flow<List<GateScanEntity>> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeFailed(schoolId) }

    suspend fun retryFailed() = gateScanDao.retryFailed(deviceSettings.schoolId.value)

    companion object {
        const val DUPLICATE_WINDOW_MILLIS = 60_000L
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
