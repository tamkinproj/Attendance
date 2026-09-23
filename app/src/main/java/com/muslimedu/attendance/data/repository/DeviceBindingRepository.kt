package com.muslimedu.attendance.data.repository

import androidx.room.withTransaction
import com.muslimedu.attendance.data.db.AppDatabase
import com.muslimedu.attendance.data.local.DeviceSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Links this device to exactly one school, the first time an admin logs in.
 *
 * Anything recorded before that - gate scans, hand-added students, face
 * templates, audit entries - was filed under [DeviceSettings.UNBOUND_SCHOOL_ID]
 * and is moved onto the real school id here, in one transaction, so it
 * uploads under the right school. After that, a login from any other
 * school is refused ([canUse]) rather than re-binding: re-binding would let
 * one school's offline scans be uploaded into another school's records.
 */
@Singleton
class DeviceBindingRepository @Inject constructor(
    private val database: AppDatabase,
    private val deviceSettings: DeviceSettings,
) {
    fun canUse(schoolId: Int): Boolean = !deviceSettings.isBound || deviceSettings.schoolId.value == schoolId

    fun mismatchMessage(): String =
        "This device is linked to school #${deviceSettings.schoolId.value}. " +
            "Sign in with an admin account from that school."

    suspend fun bindTo(schoolId: Int) {
        if (deviceSettings.isBound) return
        val from = DeviceSettings.UNBOUND_SCHOOL_ID
        database.withTransaction {
            database.studentDao().moveToSchool(from, schoolId)
            database.faceTemplateDao().moveToSchool(from, schoolId)
            database.gateScanDao().moveToSchool(from, schoolId)
            database.auditLogDao().moveToSchool(from, schoolId)
        }
        deviceSettings.bindSchool(schoolId)
    }
}
