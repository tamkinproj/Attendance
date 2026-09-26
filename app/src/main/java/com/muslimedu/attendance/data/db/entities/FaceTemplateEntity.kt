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
 *
 * A student has one row per enrolled angle ([pose], in capture order:
 * straight first, then the sides that were taken); the gate matches the
 * best of them.
 */
@Entity(
    tableName = "face_templates",
    indices = [Index(value = ["school_id", "student_id", "pose"], unique = true)],
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
    // v9. No defaultValue here on purpose (see GateScanEntity): the
    // migration's DEFAULT marks rows from before the model as MODEL_LANDMARK.
    /** Which recognizer made [encryptedEmbedding]; only [MODEL_MOBILEFACENET] templates are used. */
    @ColumnInfo(name = "model") val model: String = MODEL_MOBILEFACENET,
    // v12, same reasoning: faces enrolled before angles existed are pose 0.
    /** Capture order of this angle - 0 is the straight face; a skipped side leaves no gap. */
    @ColumnInfo(name = "pose") val pose: Int = 0,
    // v13: sharing through the school server. Every angle of one
    // registration carries the same [version] and upload state, so the
    // state travels with the face (a student download never touches it).
    /** Identifies one registration (all its angles); the other gate phones compare it. */
    @ColumnInfo(name = "version") val version: String? = null,
    /** [SYNC_PENDING] until the school server has this registration, [SYNC_FAILED] when it refused it. */
    @ColumnInfo(name = "sync_status") val syncStatus: String = SYNC_SYNCED,
    @ColumnInfo(name = "sync_error") val syncError: String? = null,
) {
    companion object {
        /** The old landmark-ratio placeholder: kept on the device, never compared with. */
        const val MODEL_LANDMARK = "landmark"
        const val MODEL_MOBILEFACENET = "mobilefacenet"
        const val SYNC_PENDING = "pending"
        const val SYNC_SYNCED = "synced"
        const val SYNC_FAILED = "failed"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceTemplateEntity) return false
        return id == other.id && schoolId == other.schoolId && studentId == other.studentId &&
            encryptedEmbedding.contentEquals(other.encryptedEmbedding) &&
            encryptionVersion == other.encryptionVersion && enrolledAt == other.enrolledAt &&
            enrolledBy == other.enrolledBy && livenessScore == other.livenessScore &&
            isActive == other.isActive && createdAt == other.createdAt && updatedAt == other.updatedAt &&
            model == other.model && pose == other.pose && version == other.version &&
            syncStatus == other.syncStatus && syncError == other.syncError
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + schoolId
        result = 31 * result + studentId
        result = 31 * result + pose
        result = 31 * result + encryptedEmbedding.contentHashCode()
        return result
    }
}
