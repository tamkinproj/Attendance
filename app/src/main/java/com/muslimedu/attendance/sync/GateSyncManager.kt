package com.muslimedu.attendance.sync

import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanRequest
import com.muslimedu.attendance.data.remote.dto.GateRejectedScanRequest
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
    /**
     * [stoppedReason] is why attendance uploading stopped early (null = all
     * sent); [endpointMissing] when that's because the server doesn't have
     * the gate endpoints yet. Card registrations report separately
     * ([cardsStoppedReason]) - they never hold up attendance.
     */
    data class Finished(
        val uploaded: Int,
        val rejected: Int,
        val stoppedReason: String?,
        val endpointMissing: Boolean = false,
        val cardsSynced: Int = 0,
        val cardsFailed: Int = 0,
        val cardsStoppedReason: String? = null,
        val phonesSynced: Int = 0,
        val phonesFailed: Int = 0,
        val phonesStoppedReason: String? = null,
    ) : GateSyncOutcome()
}

/**
 * Uploads everything recorded at this gate. Runs after each scan
 * (best-effort), right after an admin signs in, from the Sync and gate
 * screens, and from [SyncWorker] - periodically and as soon as the network
 * returns ([GateSyncScheduler]).
 *
 * In order:
 * 1. Card registrations and parent numbers entered on this device
 *    ([RfidCardSyncManager], [ParentPhoneSyncManager]).
 * 2. Attendance - face-confirmed records - to `/admin_gate_attendance_scan`,
 *    one call per record, oldest first. The backend keeps a student's first
 *    "in" as their check-in time, so this stops at the first record that
 *    couldn't be sent for a temporary reason (offline, 5xx, still backing
 *    off) instead of skipping ahead - later records wait for it. Only a
 *    record the server refuses outright (unknown code, validation) is marked
 *    failed and stepped past, since no retry could fix it.
 * 3. Failed face checks, to `/admin_gate_rejected_scan`. Logs only, so they
 *    never hold up step 2; a server without that endpoint just leaves them
 *    waiting.
 *
 * Every record carries its own event id, so an upload retried after a lost
 * response is ignored by the server rather than counted twice.
 */
@Singleton
class GateSyncManager @Inject constructor(
    private val apiService: ApiService,
    private val gateScanDao: GateScanDao,
    private val deviceSettings: DeviceSettings,
    private val tokenManager: TokenManager,
    private val rfidCardSyncManager: RfidCardSyncManager,
    private val parentPhoneSyncManager: ParentPhoneSyncManager,
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
            val cards = rfidCardSyncManager.flush()
            val phones = parentPhoneSyncManager.flush()
            var uploaded = 0
            var rejected = 0
            var stoppedReason: String? = null
            var endpointMissing = false
            val now = System.currentTimeMillis()
            val pending = gateScanDao.getPending(deviceSettings.schoolId.value)

            for (scan in pending.filter { it.outcome == GateScanEntity.OUTCOME_RECORDED }) {
                val retryAt = scan.nextRetryAt
                if (retryAt != null && retryAt > now) {
                    stoppedReason = "Waiting to retry: ${scan.errorMessage ?: "earlier error"}"
                    break
                }
                when (val result = syncAttendance(scan, now)) {
                    StepResult.Uploaded -> uploaded++
                    StepResult.Rejected -> rejected++
                    is StepResult.Stop -> {
                        stoppedReason = result.reason
                        endpointMissing = result.endpointMissing
                        break
                    }
                }
            }

            // Best-effort, and deliberately after attendance: nothing here
            // can delay a check-in reaching the server.
            for (scan in pending.filter { it.outcome == GateScanEntity.OUTCOME_REJECTED }) {
                val retryAt = scan.nextRetryAt
                if (retryAt != null && retryAt > now) break
                if (syncFailedAttempt(scan, now) is StepResult.Stop) break
            }

            GateSyncOutcome.Finished(
                uploaded = uploaded,
                rejected = rejected,
                stoppedReason = stoppedReason,
                endpointMissing = endpointMissing,
                cardsSynced = cards.synced,
                cardsFailed = cards.failed,
                cardsStoppedReason = cards.stoppedReason,
                phonesSynced = phones.synced,
                phonesFailed = phones.failed,
                phonesStoppedReason = phones.stoppedReason,
            )
        } finally {
            _isSyncing.value = false
        }
    }

    private sealed class StepResult {
        data object Uploaded : StepResult()
        data object Rejected : StepResult()
        data class Stop(val reason: String, val endpointMissing: Boolean = false) : StepResult()
    }

    private suspend fun syncAttendance(scan: GateScanEntity, now: Long): StepResult = try {
        val response = apiService.adminGateAttendanceScan(
            GateAttendanceScanRequest(
                code = scan.studentCode,
                direction = scan.direction,
                date = scan.scanDate,
                time = scan.scanTime,
                rfidUid = scan.rfidUid,
                rfidVerified = scan.rfidVerified,
                faceConfirmed = scan.verifiedByFace,
                faceScore = scan.faceMatchScore,
                deviceEventId = scan.eventId.ifEmpty { null },
                late = scan.lateAfter?.let { scan.late },
                minutesLate = scan.minutesLate,
                lateAfter = scan.lateAfter,
            ),
        )
        if (response.success) {
            markSynced(scan, now, response.data?.student?.attendanceId)
            StepResult.Uploaded
        } else {
            markRejected(scan, now, response.message ?: "Rejected by server")
        }
    } catch (e: HttpException) {
        handleHttpError(scan, now, e, notFoundMessage = "Student code not found on server")
    } catch (e: IOException) {
        // Offline: not counted as an attempt - WorkManager only runs this
        // with a network, and being offline for a day must not fail scans.
        StepResult.Stop("No connection - will upload when online")
    }

    private suspend fun syncFailedAttempt(scan: GateScanEntity, now: Long): StepResult = try {
        val response = apiService.adminGateRejectedScan(
            GateRejectedScanRequest(
                code = scan.studentCode,
                direction = scan.direction,
                date = scan.scanDate,
                time = scan.scanTime,
                rfidUid = scan.rfidUid,
                reason = scan.failureReason,
                faceScore = scan.faceMatchScore,
                deviceEventId = scan.eventId,
            ),
        )
        if (response.success) {
            markSynced(scan, now, null)
            StepResult.Uploaded
        } else {
            markRejected(scan, now, response.message ?: "Rejected by server")
        }
    } catch (e: HttpException) {
        handleHttpError(scan, now, e, notFoundMessage = "Student code not found on server")
    } catch (e: IOException) {
        StepResult.Stop("No connection")
    }

    private suspend fun handleHttpError(scan: GateScanEntity, now: Long, e: HttpException, notFoundMessage: String): StepResult {
        val message = e.extractApiErrorMessage()
        return when (e.code()) {
            // Session or account problem, not this scan's: leave it pending
            // untouched and stop - it uploads once a valid admin signs in.
            401 -> StepResult.Stop("Session expired - sign out and sign in again")
            403 -> StepResult.Stop(message ?: "This account isn't allowed to record gate attendance")
            // No active student with this code in the school, or bad input -
            // retrying can't help, so step past it.
            404, 422 -> markRejected(scan, now, message ?: notFoundMessage)
            // Route not registered on the server (Laravel's GET-only
            // fallback answers an unknown POST with 405) - not this scan's
            // fault, so keep it pending and uncounted until the backend has it.
            405, 501 -> StepResult.Stop(
                "Gate upload isn't set up on the school server yet - records are kept on this device",
                endpointMissing = true,
            )
            else -> scheduleRetry(scan, now, message ?: "Server error (${e.code()})")
        }
    }

    private suspend fun markSynced(scan: GateScanEntity, now: Long, serverAttendanceId: Int?) {
        gateScanDao.updateSyncResult(
            id = scan.id,
            syncStatus = GateScanEntity.SYNC_SYNCED,
            syncAttempts = scan.syncAttempts + 1,
            nextRetryAt = null,
            lastSyncAt = now,
            serverAttendanceId = serverAttendanceId,
            errorMessage = null,
        )
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
