package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.RosterRequest
import com.muslimedu.attendance.data.remote.dto.TeacherClassDto
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.data.session.SessionManager
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the logged-in teacher's classes and syncs a chosen class's roster
 * into the local [StudentDao] cache that
 * [com.muslimedu.attendance.viewmodel.RfidViewModel] looks students up
 * against. This is a merge, not a full reconciliation: students no longer on
 * the roster aren't removed from the local cache. Good enough for now; revisit
 * if stale entries turn out to matter in practice.
 */
@Singleton
class RosterRepository @Inject constructor(
    private val apiService: ApiService,
    private val studentDao: StudentDao,
    private val sessionManager: SessionManager,
    private val photoCache: StudentPhotoCache,
) {
    suspend fun fetchClasses(): Result<List<TeacherClassDto>> = try {
        val response = apiService.teacherAttendanceClasses()
        val classes = response.data?.classes
        when {
            !response.success -> Result.failure(Exception(response.message ?: "Could not load classes"))
            classes == null -> Result.failure(Exception("The server did not return a class list"))
            else -> Result.success(classes)
        }
    } catch (e: HttpException) {
        Result.failure(Exception(e.extractApiErrorMessage() ?: "Could not load classes"))
    } catch (e: IOException) {
        Result.failure(Exception("Network error - check your connection"))
    }

    suspend fun syncRoster(sectionId: Int, subjectId: Int): Result<Int> {
        val schoolId = sessionManager.currentUser.value?.schoolId
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val request = RosterRequest(sectionId, subjectId, LocalDate.now().toString())
            val response = apiService.teacherAttendanceRoster(request)
            if (!response.success) {
                return Result.failure(Exception(response.message ?: "Could not sync roster"))
            }
            val roster = response.data
            val students = roster?.students
                ?: return Result.failure(Exception("The server did not return a student roster"))

            val now = System.currentTimeMillis()
            val entities = students.map { student ->
                // The server has no rfid_card_number field at all (see
                // RosterDto.kt) - carry the existing local row's id and
                // rfidCardNumber forward, or this upsert silently wipes out
                // any card already assigned to this student on every re-sync.
                val existing = studentDao.findBySchoolAndStudentId(schoolId, student.studentId)
                StudentEntity(
                    id = existing?.id ?: 0,
                    schoolId = schoolId,
                    studentId = student.studentId,
                    name = student.studentName,
                    code = student.code,
                    photoUrl = student.photo,
                    gender = student.gender,
                    sectionId = roster.sectionId,
                    sectionName = roster.sectionName,
                    rfidCardNumber = existing?.rfidCardNumber,
                    isLocalOnly = false,
                    lastSyncedAt = now,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            }
            studentDao.insertAll(entities)

            // Fire-and-forget - see StudentPhotoCache.prefetch's own doc
            // comment for why this must not make the roster sync itself wait
            // on a network round trip per student.
            students.forEach { photoCache.prefetch(schoolId, it.studentId, it.photo) }

            Result.success(entities.size)
        } catch (e: HttpException) {
            Result.failure(Exception(e.extractApiErrorMessage() ?: "Could not sync roster"))
        } catch (e: IOException) {
            Result.failure(Exception("Network error - check your connection"))
        }
    }
}
