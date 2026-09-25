package com.muslimedu.attendance.data.repository

import androidx.room.withTransaction
import com.muslimedu.attendance.data.db.AppDatabase
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateStudentDto
import com.muslimedu.attendance.data.remote.dto.GateStudentsRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class StudentDownloadSummary(val added: Int, val updated: Int, val skipped: Int)

/**
 * The school server doesn't have `admin_gate_students` yet. Not an error the
 * admin can fix from the device - screens show it as "not available yet"
 * rather than a failure, and students can still be added by hand.
 */
class StudentDownloadUnavailableException : Exception(
    "Student download isn't set up on the school server yet. Add students by hand in Admin > Students until it is.",
)

/**
 * How a Laravel server answers a route that doesn't exist: 404, or 405 when
 * a GET-only catch-all (`Route::fallback()`, an SPA route) matches the URL -
 * Laravel then reports "POST not supported, supported methods: GET, HEAD"
 * instead of "not found". Both come with a JSON `message`, so the message
 * can't be used to tell them apart from a real error; the status code can.
 */
internal fun isMissingEndpoint(httpCode: Int): Boolean = httpCode == 404 || httpCode == 405 || httpCode == 501

/**
 * Downloads the whole school's student list (proposed `admin_gate_students`
 * endpoint) into the local cache so gate scanning works offline.
 *
 * Merged by `code` - the one identifier the backend and this device share.
 * A student already on the device (downloaded before, or added by hand)
 * keeps their local row, RFID card and face template; only the server-owned
 * fields are refreshed, and a hand-added student's temporary negative id is
 * replaced by the real one (their face template follows). A merge, not a
 * reconciliation: a student missing from the download is left in place.
 */
@Singleton
class StudentDownloadRepository @Inject constructor(
    private val apiService: ApiService,
    private val database: AppDatabase,
    private val deviceSettings: DeviceSettings,
    private val photoCache: StudentPhotoCache,
) {
    suspend fun download(): Result<StudentDownloadSummary> {
        if (!deviceSettings.isBound) return Result.failure(Exception("Sign in as a school admin first"))
        val students = try {
            val response = apiService.adminGateStudents(GateStudentsRequest())
            if (!response.success) return Result.failure(Exception(response.message ?: "Download failed"))
            response.data?.students ?: return Result.failure(Exception("The server did not return a student list"))
        } catch (e: HttpException) {
            if (isMissingEndpoint(e.code())) return Result.failure(StudentDownloadUnavailableException())
            return Result.failure(Exception(e.extractApiErrorMessage() ?: "Download failed (${e.code()})"))
        } catch (e: IOException) {
            return Result.failure(Exception("Network error - check your connection"))
        }

        val schoolId = deviceSettings.schoolId.value
        val summary = merge(schoolId, students)
        deviceSettings.lastStudentDownloadAt = System.currentTimeMillis()
        students.forEach { photoCache.prefetch(schoolId, it.studentId, it.photo) }
        return Result.success(summary)
    }

    private suspend fun merge(schoolId: Int, students: List<GateStudentDto>): StudentDownloadSummary {
        var added = 0
        var updated = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val studentDao = database.studentDao()
        val faceTemplateDao = database.faceTemplateDao()

        database.withTransaction {
            for (dto in students) {
                val code = dto.code?.trim()
                if (code.isNullOrEmpty()) {
                    skipped++
                    continue
                }
                val existing = studentDao.findByCode(code)
                if (existing != null && existing.schoolId != schoolId) {
                    // Code is taken by another school's leftover cache row
                    // (unique index is table-wide) - don't overwrite it.
                    skipped++
                    continue
                }
                if (existing != null && existing.studentId != dto.studentId) {
                    faceTemplateDao.reassignStudent(schoolId, existing.studentId, dto.studentId)
                }
                studentDao.insert(
                    StudentEntity(
                        id = existing?.id ?: 0,
                        schoolId = schoolId,
                        studentId = dto.studentId,
                        name = dto.name ?: existing?.name ?: code,
                        code = code,
                        photoUrl = dto.photo ?: existing?.photoUrl,
                        gender = dto.gender ?: existing?.gender,
                        sectionId = dto.sectionId ?: existing?.sectionId,
                        sectionName = dto.sectionName ?: existing?.sectionName,
                        rfidCardNumber = existing?.rfidCardNumber,
                        isLocalOnly = false,
                        lastSyncedAt = now,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
                if (existing == null) added++ else updated++
            }
        }
        return StudentDownloadSummary(added, updated, skipped)
    }
}
