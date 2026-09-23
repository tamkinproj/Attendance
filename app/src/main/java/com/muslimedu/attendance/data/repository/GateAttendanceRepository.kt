package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

sealed class GateRecordResult {
    data class Recorded(val scan: GateScanEntity) : GateRecordResult()

    /** Same student, same direction, moments ago - a double tap, not a new event. */
    data class Duplicate(val existing: GateScanEntity) : GateRecordResult()
}

/**
 * Offline-first gate in/out attendance: every scan is written to the local
 * `gate_scans` table immediately, with no network and no login needed, and
 * uploaded later by [com.muslimedu.attendance.sync.GateSyncManager]. The
 * "today" list is read from the same table, so it works offline too and
 * reflects this device's own scans (not other gates' - that view is the
 * web app's job).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GateAttendanceRepository @Inject constructor(
    private val gateScanDao: GateScanDao,
    private val deviceSettings: DeviceSettings,
) {
    suspend fun record(
        code: String,
        studentName: String?,
        direction: String,
        verifiedByFace: Boolean,
        faceMatchScore: Float?,
        now: LocalDateTime = LocalDateTime.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): GateRecordResult {
        val schoolId = deviceSettings.schoolId.value
        val latest = gateScanDao.latestForCode(schoolId, code)
        if (latest != null && latest.direction == direction && nowMillis - latest.scannedAt < DUPLICATE_WINDOW_MILLIS) {
            return GateRecordResult.Duplicate(latest)
        }
        val scan = GateScanEntity(
            schoolId = schoolId,
            studentCode = code,
            studentName = studentName,
            direction = direction,
            scanDate = now.toLocalDate().toString(),
            scanTime = now.format(TIME_FORMAT),
            scannedAt = nowMillis,
            verifiedByFace = verifiedByFace,
            faceMatchScore = faceMatchScore,
        )
        val id = gateScanDao.insert(scan)
        return GateRecordResult.Recorded(scan.copy(id = id))
    }

    fun observeToday(): Flow<List<GateScanEntity>> =
        deviceSettings.schoolId.flatMapLatest { schoolId ->
            gateScanDao.observeForDate(schoolId, LocalDate.now().toString())
        }

    fun observeCount(syncStatus: String): Flow<Int> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeCount(schoolId, syncStatus) }

    fun observeFailed(): Flow<List<GateScanEntity>> =
        deviceSettings.schoolId.flatMapLatest { schoolId -> gateScanDao.observeFailed(schoolId) }

    suspend fun retryFailed() = gateScanDao.retryFailed(deviceSettings.schoolId.value)

    companion object {
        private const val DUPLICATE_WINDOW_MILLIS = 60_000L
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
