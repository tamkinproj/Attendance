package com.muslimedu.attendance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.AuditLogEntity

@Dao
interface AuditLogDao {

    @Insert
    suspend fun insert(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<AuditLogEntity>

    /** Scoped variant for [com.muslimedu.attendance.ui.screens.admin.AuditLogScreen] - [getRecent] has no schoolId filter, so a device that's had more than one account on it would otherwise show every school's entries mixed together. */
    @Query("SELECT * FROM audit_logs WHERE school_id = :schoolId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentForSchool(schoolId: Int, limit: Int = 200): List<AuditLogEntity>

    @Query("UPDATE audit_logs SET school_id = :toSchoolId WHERE school_id = :fromSchoolId")
    suspend fun moveToSchool(fromSchoolId: Int, toSchoolId: Int)
}
