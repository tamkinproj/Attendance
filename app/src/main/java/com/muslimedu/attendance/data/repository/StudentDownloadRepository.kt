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
            val message = if (e.code() == 404 && e.extractApiErrorMessage() == null) {
                "The server doesn't support student download yet (admin_gate_students) - add students by hand for now"
            } else {
                e.extractApiErrorMessage() ?: "Download failed (${e.code()})"
            }
            return Result.failure(Exception(message))
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
