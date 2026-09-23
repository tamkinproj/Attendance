package com.muslimedu.attendance.sync

import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.security.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class GateSyncOutcome {
    /** Nothing sent - scans stay safely on the device until an admin signs in. */
    data object NotSignedIn : GateSyncOutcome()
    data class Finished(val uploaded: Int, val rejected: Int, val stoppedReason: String?) : GateSyncOutcome()
}

/**
 * Uploads locally recorded [GateScanEntity] rows to `/admin_gate_attendance_scan`,
 * one call per scan, oldest first. Runs after each scan (best-effort), right
 * after an admin signs in, from the Sync screen, and periodically from
 * [SyncWorker].
 *
 * Order matters: the backend keeps a student's first "in" as their check-in
 * time and appends every event to a list, so this stops at the first scan
 * that couldn't be sent for a temporary reason (offline, 5xx, still backing
 * off) instead of skipping ahead - later scans wait for it. Only a scan the
 * server rejects outright (unknown code, validation) is marked failed and
 * stepped past, since no retry could fix it.
 *
 * Nothing here needs a server student id: a scan is identified by the
 * student's `code` alone, which the backend resolves school-wide.
 */
@Singleton
class GateSyncManager @Inject constructor(
    private val apiService: ApiService,
    private val gateScanDao: GateScanDao,
    private val deviceSettings: DeviceSettings,
    private val tokenManager: TokenManager,
) {
    private val mutex = Mutex()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    suspend fun flush(): GateSyncOutcome {
        if (tokenManager.getToken() == null || !deviceSettings.isBound) return GateSyncOutcome.NotSignedIn
        return mutex.withLock { flushLocked() }
    }

    private suspend fun flushLocked(): GateSyncOutcome {
        _isSyncing.value = true
        return try {
            var uploaded = 0
            var rejected = 0
            var stoppedReason: String? = null
            val now = System.currentTimeMillis()
            for (scan in gateScanDao.getPending(deviceSettings.schoolId.value)) {
                val retryAt = scan.nextRetryAt
                if (retryAt != null && retryAt > now) {
                    stoppedReason = "Waiting to retry: ${scan.errorMessage ?: "earlier error"}"
                    break
                }
                when (val result = syncOne(scan, now)) {
                    StepResult.Uploaded -> uploaded++
                    StepResult.Rejected -> rejected++
                    is StepResult.Stop -> {
                        stoppedReason = result.reason
                        break
                    }
                }
            }
            GateSyncOutcome.Finished(uploaded, rejected, stoppedReason)
        } finally {
            _isSyncing.value = false
        }
    }

    private sealed class StepResult {
        data object Uploaded : StepResult()
        data object Rejected : StepResult()
        data class Stop(val reason: String) : StepResult()
    }

    private suspend fun syncOne(scan: GateScanEntity, now: Long): StepResult = try {
        val response = apiService.adminGateAttendanceScan(
            GateAttendanceScanRequest(
                code = scan.studentCode,
                direction = scan.direction,
                date = scan.scanDate,
                time = scan.scanTime,
            ),
        )
        if (response.success) {
            gateScanDao.updateSyncResult(
                id = scan.id,
                syncStatus = GateScanEntity.SYNC_SYNCED,
                syncAttempts = scan.syncAttempts + 1,
                nextRetryAt = null,
                lastSyncAt = now,
                serverAttendanceId = response.data?.student?.attendanceId,
                errorMessage = null,
            )
            StepResult.Uploaded
        } else {
            markRejected(scan, now, response.message ?: "Rejected by server")
        }
    } catch (e: HttpException) {
        val message = e.extractApiErrorMessage()
        when (e.code()) {
            // Session or account problem, not this scan's: leave it pending
            // untouched and stop - it uploads once a valid admin signs in.
            401 -> StepResult.Stop("Session expired - sign in again on the Sync screen")
            403 -> StepResult.Stop(message ?: "This account isn't allowed to record gate attendance")
            // No active student with this code in the school, or bad input -
            // retrying can't help, so step past it.
            404, 422 -> markRejected(scan, now, message ?: "Student code not found on server")
            else -> scheduleRetry(scan, now, message ?: "Server error (${e.code()})")
        }
    } catch (e: IOException) {
        // Offline: not counted as an attempt - WorkManager only runs this
        // with a network, and being offline for a day must not fail scans.
        StepResult.Stop("No connection - will upload when online")
    }

    private suspend fun markRejected(scan: GateScanEntity, now: Long, message: String): StepResult {
        gateScanDao.updateSyncResult(
            id = scan.id,
            syncStatus = GateScanEntity.SYNC_FAILED,
            syncAttempts = scan.syncAttempts + 1,
            nextRetryAt = null,
            lastSyncAt = now,
            serverAttendanceId = null,
            errorMessage = message,
        )
        return StepResult.Rejected
    }

    private suspend fun scheduleRetry(scan: GateScanEntity, now: Long, message: String): StepResult {
        val attempts = scan.syncAttempts + 1
        if (attempts >= RetryStrategy.MAX_ATTEMPTS) {
            return markRejected(scan, now, "$message (gave up after $attempts attempts)")
        }
        gateScanDao.updateSyncResult(
            id = scan.id,
            syncStatus = GateScanEntity.SYNC_PENDING,
            syncAttempts = attempts,
            nextRetryAt = RetryStrategy.nextRetryAt(scan.syncAttempts, now),
            lastSyncAt = now,
            serverAttendanceId = null,
            errorMessage = message,
        )
        return StepResult.Stop(message)
    }
}
