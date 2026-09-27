package com.muslimedu.attendance.sync

import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.ParentPhoneSetRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.security.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ParentPhoneSyncOutcome(val synced: Int, val failed: Int, val stoppedReason: String?)

/**
 * Sends parent numbers entered on this device to `/admin_set_parent_phone`,
 * where they're saved on the student's parent account - the number the
 * gate texts go to. Same shape as [RfidCardSyncManager]: the current number
 * is sent as the desired state (retries are harmless), a refusal the admin
 * has to fix (no parent account linked, unknown student) is marked failed
 * with the server's reason, and anything temporary leaves it pending.
 */
@Singleton
class ParentPhoneSyncManager @Inject constructor(
    private val apiService: ApiService,
    private val studentDao: StudentDao,
    private val deviceSettings: DeviceSettings,
    private val tokenManager: TokenManager,
) {
    private val mutex = Mutex()

    suspend fun flush(): ParentPhoneSyncOutcome {
        if (tokenManager.getToken() == null || !deviceSettings.isBound) return ParentPhoneSyncOutcome(0, 0, null)
        return mutex.withLock {
            var synced = 0
            var failed = 0
            var stoppedReason: String? = null
            for (student in studentDao.getPhonePending(deviceSettings.schoolId.value)) {
                when (val result = syncOne(student)) {
                    Step.Synced -> synced++
                    Step.Failed -> failed++
                    is Step.Stop -> {
                        stoppedReason = result.reason
                        break
                    }
                }
            }
            ParentPhoneSyncOutcome(synced, failed, stoppedReason)
        }
    }

    private sealed class Step {
        data object Synced : Step()
        data object Failed : Step()
        data class Stop(val reason: String) : Step()
    }

    private suspend fun syncOne(student: StudentEntity): Step {
        val phone = student.parentPhone
        return try {
            val response = apiService.adminSetParentPhone(ParentPhoneSetRequest(code = student.code, phone = phone))
            if (response.success) {
                studentDao.updatePhoneSyncState(student.id, phone, StudentEntity.RFID_SYNCED, null)
                studentDao.setHasParentAccount(student.id, true)
                Step.Synced
            } else {
                fail(student, phone, response.message ?: "Rejected by server")
            }
        } catch (e: HttpException) {
            val message = e.extractApiErrorMessage()
            when (e.code()) {
                401 -> Step.Stop("Session expired - sign out and sign in again")
                403 -> Step.Stop(message ?: "This account can't save parent numbers")
                404 -> fail(student, phone, message ?: "Student code ${student.code} isn't on the school server")
                422 -> fail(student, phone, message ?: "The server refused this number")
                405, 501 -> Step.Stop("Parent numbers can't be saved on the school server yet - they stay on this device")
                else -> Step.Stop(message ?: "Server error (${e.code()})")
            }
        } catch (e: IOException) {
            Step.Stop("No connection - will upload when online")
        }
    }

    private suspend fun fail(student: StudentEntity, phone: String?, reason: String): Step {
        studentDao.updatePhoneSyncState(student.id, phone, StudentEntity.RFID_FAILED, reason)
        if (reason.contains("no linked parent", ignoreCase = true)) studentDao.setHasParentAccount(student.id, false)
        return Step.Failed
    }
}
