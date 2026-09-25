package com.muslimedu.attendance.sync

import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.StudentRfidSetRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.security.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class RfidCardSyncOutcome(val synced: Int, val failed: Int, val stoppedReason: String?)

/**
 * Sends card registrations made on this device (assign, replace, remove) to
 * the server's card registry, `/admin_student_rfid_set`, so the web admin
 * sees each student's card and every gate device agrees on who owns it.
 *
 * Each student's current card is sent as the desired state, so retries are
 * harmless. A card the server says belongs to someone else (409), or a
 * student code it doesn't know (404), is marked failed with the server's
 * reason - the admin has to resolve it. Anything temporary (offline, server
 * error, the registry not deployed yet) leaves the change pending.
 */
@Singleton
class RfidCardSyncManager @Inject constructor(
    private val apiService: ApiService,
    private val studentDao: StudentDao,
    private val deviceSettings: DeviceSettings,
    private val tokenManager: TokenManager,
) {
    private val mutex = Mutex()

    suspend fun flush(): RfidCardSyncOutcome {
        if (tokenManager.getToken() == null || !deviceSettings.isBound) return RfidCardSyncOutcome(0, 0, null)
        return mutex.withLock {
            var synced = 0
            var failed = 0
            var stoppedReason: String? = null
            for (student in studentDao.getRfidPending(deviceSettings.schoolId.value)) {
                when (val result = syncOne(student)) {
                    Step.Synced -> synced++
                    Step.Failed -> failed++
                    is Step.Stop -> {
                        stoppedReason = result.reason
                        break
                    }
                }
            }
            RfidCardSyncOutcome(synced, failed, stoppedReason)
        }
    }

    private sealed class Step {
        data object Synced : Step()
        data object Failed : Step()
        data class Stop(val reason: String) : Step()
    }

    private suspend fun syncOne(student: StudentEntity): Step {
        val uid = student.rfidCardNumber
        val request = if (uid != null) {
            StudentRfidSetRequest(code = student.code, action = StudentRfidSetRequest.ACTION_ASSIGN, rfidUid = uid)
        } else {
            StudentRfidSetRequest(code = student.code, action = StudentRfidSetRequest.ACTION_REMOVE)
        }
        return try {
            val response = apiService.adminStudentRfidSet(request)
            if (response.success) {
                studentDao.updateRfidSyncState(student.id, uid, StudentEntity.RFID_SYNCED, null)
                Step.Synced
            } else {
                fail(student, uid, response.message ?: "Rejected by server")
            }
        } catch (e: HttpException) {
            val message = e.extractApiErrorMessage()
            when (e.code()) {
                401 -> Step.Stop("Session expired - sign out and sign in again")
                403 -> Step.Stop(message ?: "This account can't register cards")
                404 -> fail(student, uid, message ?: "Student code ${student.code} isn't on the school server")
                409, 422 -> fail(student, uid, message ?: "The server refused this card")
                405, 501 -> Step.Stop("Card registration isn't set up on the school server yet - cards stay on this device")
                else -> Step.Stop(message ?: "Server error (${e.code()})")
            }
        } catch (e: IOException) {
            Step.Stop("No connection - will upload when online")
        }
    }

    private suspend fun fail(student: StudentEntity, uid: String?, reason: String): Step {
        studentDao.updateRfidSyncState(student.id, uid, StudentEntity.RFID_FAILED, reason)
        return Step.Failed
    }
}
