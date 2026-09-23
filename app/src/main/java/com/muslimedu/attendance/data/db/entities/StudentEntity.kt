package com.muslimedu.attendance.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local offline cache of a student's roster row, keyed for fast RFID lookup.
 * Mirrors the subset of the `students` table (see project spec) this app
 * needs for scan-time identification. Populated either by
 * [com.muslimedu.attendance.data.repository.RosterRepository] syncing
 * `/teacher_attendance_roster`, or - if that hasn't run yet, e.g. no
 * backend access during development - by
 * [com.muslimedu.attendance.data.repository.StudentRepository]'s sample-data
 * seed. Rows added on-device via
 * [com.muslimedu.attendance.data.repository.StudentRepository.addLocalStudent]
 * are flagged [isLocalOnly] and given a negative [studentId] - there is no
 * backend endpoint to create a student, so these exist only on this device
 * and are never reconciled against a real server ID.
 */
@Entity(
    tableName = "students",
    indices = [
        Index(value = ["rfid_card_number"], unique = true),
        Index(value = ["code"], unique = true),
    ],
)
data class StudentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "school_id") val schoolId: Int,
    @ColumnInfo(name = "student_id") val studentId: Int,
    val name: String,
    val code: String,
    val email: String? = null,
    @ColumnInfo(name = "photo_url") val photoUrl: String? = null,
    val gender: String? = null,
    val address: String? = null,
    @ColumnInfo(name = "section_id") val sectionId: Int? = null,
    @ColumnInfo(name = "section_name") val sectionName: String? = null,
    @ColumnInfo(name = "rfid_card_number") val rfidCardNumber: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "is_local_only", defaultValue = "0") val isLocalOnly: Boolean = false,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
