package com.muslimedu.attendance.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity

private const val CURRENT_MODEL = FaceTemplateEntity.MODEL_MOBILEFACENET

/** How many angles a student has enrolled, for a list badge - never the embeddings themselves. */
data class FaceAngleCount(
    @ColumnInfo(name = "school_id") val schoolId: Int,
    @ColumnInfo(name = "student_id") val studentId: Int,
    @ColumnInfo(name = "angles") val angles: Int,
)

/**
 * Lookups only see templates from the current recognizer
 * ([FaceTemplateEntity.MODEL_MOBILEFACENET]): a student whose face was
 * enrolled with the old placeholder counts as not enrolled until they are
 * enrolled again (which replaces the old row).
 */
@Dao
interface FaceTemplateDao {

    @Query("SELECT * FROM face_templates WHERE school_id = :schoolId AND student_id = :studentId AND is_active = 1 AND model = '$CURRENT_MODEL' LIMIT 1")
    suspend fun findForStudent(schoolId: Int, studentId: Int): FaceTemplateEntity?

    /** Every enrolled angle of one student - the gate matches the best. */
    @Query("SELECT * FROM face_templates WHERE school_id = :schoolId AND student_id = :studentId AND is_active = 1 AND model = '$CURRENT_MODEL' ORDER BY pose")
    suspend fun findAllForStudent(schoolId: Int, studentId: Int): List<FaceTemplateEntity>

    @Query("SELECT * FROM face_templates WHERE school_id = :schoolId AND is_active = 1 AND model = '$CURRENT_MODEL'")
    suspend fun activeForSchool(schoolId: Int): List<FaceTemplateEntity>

    /** Students (not angles) with a face. */
    @Query("SELECT COUNT(DISTINCT student_id) FROM face_templates WHERE school_id = :schoolId AND is_active = 1 AND model = '$CURRENT_MODEL'")
    suspend fun countActiveForSchool(schoolId: Int): Int

    @Query(
        "SELECT school_id, student_id, COUNT(*) AS angles FROM face_templates " +
            "WHERE is_active = 1 AND model = '$CURRENT_MODEL' GROUP BY school_id, student_id",
    )
    suspend fun angleCounts(): List<FaceAngleCount>

    /** Students whose registration the school server doesn't have yet. */
    @Query(
        "SELECT DISTINCT student_id FROM face_templates WHERE school_id = :schoolId AND sync_status = 'pending' " +
            "AND is_active = 1 AND model = '$CURRENT_MODEL'",
    )
    suspend fun pendingStudentIds(schoolId: Int): List<Int>

    /** Students with a registration not on the server (waiting or refused) - for the Sync screen. */
    @Query(
        "SELECT COUNT(DISTINCT student_id) FROM face_templates WHERE school_id = :schoolId AND sync_status != 'synced' " +
            "AND is_active = 1 AND model = '$CURRENT_MODEL'",
    )
    suspend fun unsentCount(schoolId: Int): Int

    /** Only rows of [version]: a registration replaced while it was uploading stays pending. */
    @Query(
        "UPDATE face_templates SET sync_status = :status, sync_error = :error " +
            "WHERE school_id = :schoolId AND student_id = :studentId AND version = :version",
    )
    suspend fun updateSyncState(schoolId: Int, studentId: Int, version: String, status: String, error: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: FaceTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(templates: List<FaceTemplateEntity>)

    /** Every row of one student, old model included - a new enrollment replaces them all. */
    @Query("DELETE FROM face_templates WHERE school_id = :schoolId AND student_id = :studentId")
    suspend fun deleteForStudent(schoolId: Int, studentId: Int)

    @Query("UPDATE face_templates SET school_id = :toSchoolId WHERE school_id = :fromSchoolId")
    suspend fun moveToSchool(fromSchoolId: Int, toSchoolId: Int)

    /** Keeps a face enrolled against a hand-added student (negative id) attached once a download gives that student their real server id. */
    @Query("UPDATE face_templates SET student_id = :newStudentId WHERE school_id = :schoolId AND student_id = :oldStudentId")
    suspend fun reassignStudent(schoolId: Int, oldStudentId: Int, newStudentId: Int)
}
