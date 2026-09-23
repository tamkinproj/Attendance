package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateAttendanceRecordDto
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanRequest
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanStudentDto
import com.muslimedu.attendance.data.remote.dto.GateAttendanceTodayRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admin-only, campus-wide gate in/out attendance - live network calls only,
 * no local caching or offline queue like [AttendanceRepository]/
 * [com.muslimedu.attendance.sync.SyncQueueManager]. Unlike the RFID scan
 * flow, this is deliberately not offline-first: a gate scan resolves the
 * student server-side by `code` against the WHOLE school, so there is no
 * local roster cache this could fall back to matching against, and no
 * meaningful way to defer "did this student enter the campus" to a later
 * sync. Same "network-only, admin-gated" shape as [AdminDirectoryRepository].
 */
@Singleton
class GateAttendanceRepository @Inject constructor(
    private val apiService: ApiService,
) {
    suspend fun scan(code: String, direction: String): Result<GateAttendanceScanStudentDto> = try {
        val response = apiService.adminGateAttendanceScan(GateAttendanceScanRequest(code = code, direction = direction))
        val student = response.data?.student
        when {
            !response.success -> Result.failure(Exception(response.message ?: "Gate scan failed"))
            student == null -> Result.failure(Exception(response.message ?: "The server did not return a student"))
            else -> Result.success(student)
        }
    } catch (e: HttpException) {
        Result.failure(Exception(e.extractApiErrorMessage() ?: "Gate scan failed"))
    } catch (e: IOException) {
        Result.failure(Exception("Network error - check your connection"))
    }

    suspend fun today(): Result<List<GateAttendanceRecordDto>> = try {
        val response = apiService.adminGateAttendanceToday(GateAttendanceTodayRequest())
        val students = response.data?.students
        when {
            !response.success -> Result.failure(Exception(response.message ?: "Could not load today's gate attendance"))
            students == null -> Result.failure(Exception("The server did not return a student list"))
            else -> Result.success(students)
        }
    } catch (e: HttpException) {
        Result.failure(Exception(e.extractApiErrorMessage() ?: "Could not load today's gate attendance"))
    } catch (e: IOException) {
        Result.failure(Exception("Network error - check your connection"))
    }
}
