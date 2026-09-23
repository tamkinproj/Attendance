package com.muslimedu.attendance.security

import android.content.Context
import android.provider.Settings
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.entities.AuditLogEntity
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records admin-relevant actions (login/logout, attendance recorded, manual
 * overrides, face/RFID enrollment) to the local `audit_logs` table.
 *
 * Every entry needs a `school_id`, taken from [SessionManager]'s current
 * user - a failed login attempt has no session yet, so those aren't logged
 * here (there's no tenant to attach them to). Log a successful login itself
 * *after* the session is populated, and log a logout *before* the session
 * is cleared - callers need to get that ordering right themselves.
 */
@Singleton
class AuditLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLogDao: AuditLogDao,
    private val sessionManager: SessionManager,
) {
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    suspend fun log(
        action: String,
        entityType: String? = null,
        entityId: Int? = null,
        details: String? = null,
        success: Boolean = true,
        errorMessage: String? = null,
    ) {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        auditLogDao.insert(
            AuditLogEntity(
                schoolId = schoolId,
                action = action,
                entityType = entityType,
                entityId = entityId,
                userId = sessionManager.currentUser.value?.email,
                details = details,
                status = if (success) AuditLogEntity.STATUS_SUCCESS else AuditLogEntity.STATUS_FAILURE,
                errorMessage = errorMessage,
                deviceId = deviceId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val ACTION_LOGIN = "login"
        const val ACTION_LOGOUT = "logout"
        const val ACTION_ATTENDANCE_RECORDED = "attendance_recorded"
        const val ACTION_MANUAL_OVERRIDE = "manual_override"
        const val ACTION_FACE_ENROLLED = "face_enrolled"
        const val ACTION_RFID_ASSIGNED = "rfid_assigned"
        const val ACTION_STUDENT_ADDED = "student_added"
    }
}
