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
    /**
     * Whether [rfidCardNumber] (a card, or null for "no card") still has to
     * reach the server's card registry - [RFID_PENDING], [RFID_SYNCED], or
     * [RFID_FAILED] with the reason in [rfidSyncError] (e.g. the card is
     * registered to another student there). A download never overwrites a
     * pending or failed card: the admin's change on this device wins until
     * it has been sent.
     */
    @ColumnInfo(name = "rfid_sync_status") val rfidSyncStatus: String = RFID_SYNCED,
    @ColumnInfo(name = "rfid_sync_error") val rfidSyncError: String? = null,
    /**
     * The parent's mobile number the gate texts go to, stored as
     * 09XXXXXXXXX (see normalizePhMobile). It lives on the parent's account
     * on the server; this is the device's copy, kept for offline display.
     */
    @ColumnInfo(name = "parent_phone") val parentPhone: String? = null,
    /** Whether the server has a parent account linked to this student - null until a download says. */
    @ColumnInfo(name = "has_parent_account") val hasParentAccount: Boolean? = null,
    /** Same pending/synced/failed cycle as [rfidSyncStatus], for [parentPhone]. */
    @ColumnInfo(name = "phone_sync_status") val phoneSyncStatus: String = RFID_SYNCED,
    @ColumnInfo(name = "phone_sync_error") val phoneSyncError: String? = null,
) {
    companion object {
        const val RFID_PENDING = "pending"
        const val RFID_SYNCED = "synced"
        const val RFID_FAILED = "failed"
    }
}
