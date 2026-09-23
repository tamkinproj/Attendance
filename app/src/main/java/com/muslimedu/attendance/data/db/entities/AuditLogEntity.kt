package com.muslimedu.attendance.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_logs",
    indices = [Index(value = ["action"]), Index(value = ["created_at"])],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "school_id") val schoolId: Int,
    val action: String,
    @ColumnInfo(name = "entity_type") val entityType: String? = null,
    @ColumnInfo(name = "entity_id") val entityId: Int? = null,
    @ColumnInfo(name = "user_id") val userId: String? = null,
    val details: String? = null,
    val status: String,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILURE = "failure"
    }
}
