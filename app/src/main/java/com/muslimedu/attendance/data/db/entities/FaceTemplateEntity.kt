package com.muslimedu.attendance.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [encryptedEmbedding] is the AES-256-GCM ciphertext (via
 * [com.muslimedu.attendance.security.EncryptionHelper]) of the raw bytes of a
 * [com.muslimedu.attendance.face.FaceTemplate]'s FloatArray embedding - never
 * a photo, matching the spec's "no photo storage" requirement.
 */
@Entity(
    tableName = "face_templates",
    indices = [Index(value = ["school_id", "student_id"], unique = true)],
)
data class FaceTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "school_id") val schoolId: Int,
    @ColumnInfo(name = "student_id") val studentId: Int,
    @ColumnInfo(name = "encrypted_embedding") val encryptedEmbedding: ByteArray,
    @ColumnInfo(name = "encryption_version") val encryptionVersion: Int = 1,
    @ColumnInfo(name = "enrolled_at") val enrolledAt: Long,
    @ColumnInfo(name = "enrolled_by") val enrolledBy: String? = null,
    @ColumnInfo(name = "liveness_score") val livenessScore: Float,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceTemplateEntity) return false
        return id == other.id && schoolId == other.schoolId && studentId == other.studentId &&
            encryptedEmbedding.contentEquals(other.encryptedEmbedding) &&
            encryptionVersion == other.encryptionVersion && enrolledAt == other.enrolledAt &&
            enrolledBy == other.enrolledBy && livenessScore == other.livenessScore &&
            isActive == other.isActive && createdAt == other.createdAt && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + schoolId
        result = 31 * result + studentId
        result = 31 * result + encryptedEmbedding.contentHashCode()
        return result
    }
}
