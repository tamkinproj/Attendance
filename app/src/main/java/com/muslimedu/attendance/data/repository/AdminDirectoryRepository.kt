package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.AdminClassSummaryDto
import com.muslimedu.attendance.data.remote.dto.AdminClassesListRequest
import com.muslimedu.attendance.data.remote.dto.AdminSectionDto
import com.muslimedu.attendance.data.remote.dto.AdminSectionStudentDto
import com.muslimedu.attendance.data.remote.dto.AdminSectionStudentsRequest
import com.muslimedu.attendance.data.remote.dto.AdminSectionsListRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only "browse the whole school" directory for admins: every class,
 * every section within a class, every student within a section - live from
 * the network on every call, no local caching. Deliberately separate from
 * [RosterRepository] (which syncs one teacher's *active* class into the
 * shared [com.muslimedu.attendance.data.db.dao.StudentDao] cache the RFID
 * scan flow depends on): `/admin_section_students`' rows have no `code`
 * field at all, so there is nothing valid to write into that table for a
 * student who only shows up here. See [AdminClassSummaryDto]'s doc comment
 * for the full reasoning.
 *
 * A 403 from any of these three calls almost always means the logged-in
 * user is a superadmin, not a plain admin - the real backend's
 * `requireAdmin()` gate checks `role_id === 2` specifically, stricter than
 * this app's own admin-or-superadmin check that got them into the Admin
 * Dashboard in the first place.
 */
@Singleton
class AdminDirectoryRepository @Inject constructor(
    private val apiService: ApiService,
) {
    suspend fun fetchClasses(): Result<List<AdminClassSummaryDto>> = try {
        val response = apiService.adminClassesList(AdminClassesListRequest())
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

    suspend fun fetchSections(classId: Int): Result<List<AdminSectionDto>> = try {
        val response = apiService.adminSectionsList(AdminSectionsListRequest(classId))
        val sections = response.data?.sections
        when {
            !response.success -> Result.failure(Exception(response.message ?: "Could not load sections"))
            sections == null -> Result.failure(Exception("The server did not return a section list"))
            else -> Result.success(sections)
        }
    } catch (e: HttpException) {
        Result.failure(Exception(e.extractApiErrorMessage() ?: "Could not load sections"))
    } catch (e: IOException) {
        Result.failure(Exception("Network error - check your connection"))
    }

    suspend fun fetchSectionStudents(sectionId: Int): Result<List<AdminSectionStudentDto>> = try {
        val response = apiService.adminSectionStudents(AdminSectionStudentsRequest(sectionId))
        val students = response.data?.students
        when {
            !response.success -> Result.failure(Exception(response.message ?: "Could not load students"))
            students == null -> Result.failure(Exception("The server did not return a student list"))
            else -> Result.success(students)
        }
    } catch (e: HttpException) {
        Result.failure(Exception(e.extractApiErrorMessage() ?: "Could not load students"))
    } catch (e: IOException) {
        Result.failure(Exception("Network error - check your connection"))
    }
}
