package com.muslimedu.attendance.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity

private const val CURRENT_MODEL = FaceTemplateEntity.MODEL_MOBILEFACENET

/** Just enough of a row to tell which student it belongs to, for a list badge - never the embedding itself. */
data class FaceTemplateKey(
    @ColumnInfo(name = "school_id") val schoolId: Int,
    @ColumnInfo(name = "student_id") val studentId: Int,
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

    @Query("SELECT * FROM face_templates WHERE school_id = :schoolId AND is_active = 1 AND model = '$CURRENT_MODEL'")
    suspend fun activeForSchool(schoolId: Int): List<FaceTemplateEntity>

    @Query("SELECT COUNT(*) FROM face_templates WHERE school_id = :schoolId AND is_active = 1 AND model = '$CURRENT_MODEL'")
    suspend fun countActiveForSchool(schoolId: Int): Int

    @Query("SELECT school_id, student_id FROM face_templates WHERE is_active = 1 AND model = '$CURRENT_MODEL'")
    suspend fun activeKeys(): List<FaceTemplateKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: FaceTemplateEntity)

    @Query("UPDATE face_templates SET school_id = :toSchoolId WHERE school_id = :fromSchoolId")
    suspend fun moveToSchool(fromSchoolId: Int, toSchoolId: Int)

    /** Keeps a face enrolled against a hand-added student (negative id) attached once a download gives that student their real server id. */
    @Query("UPDATE face_templates SET student_id = :newStudentId WHERE school_id = :schoolId AND student_id = :oldStudentId")
    suspend fun reassignStudent(schoolId: Int, oldStudentId: Int, newStudentId: Int)
}
