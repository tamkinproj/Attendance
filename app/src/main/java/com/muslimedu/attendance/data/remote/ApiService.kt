package com.muslimedu.attendance.data.remote

import com.muslimedu.attendance.data.remote.dto.AdminClassesListData
import com.muslimedu.attendance.data.remote.dto.AdminClassesListRequest
import com.muslimedu.attendance.data.remote.dto.AdminSectionStudentsData
import com.muslimedu.attendance.data.remote.dto.AdminSectionStudentsRequest
import com.muslimedu.attendance.data.remote.dto.AdminSectionsListData
import com.muslimedu.attendance.data.remote.dto.AdminSectionsListRequest
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.remote.dto.AttendanceScanData
import com.muslimedu.attendance.data.remote.dto.AttendanceScanRequest
import com.muslimedu.attendance.data.remote.dto.AttendanceSubmitData
import com.muslimedu.attendance.data.remote.dto.AttendanceSubmitRequest
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanData
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanRequest
import com.muslimedu.attendance.data.remote.dto.GateAttendanceTodayData
import com.muslimedu.attendance.data.remote.dto.GateAttendanceTodayRequest
import com.muslimedu.attendance.data.remote.dto.LoginData
import com.muslimedu.attendance.data.remote.dto.LoginRequest
import com.muslimedu.attendance.data.remote.dto.MeData
import com.muslimedu.attendance.data.remote.dto.RefreshTokenData
import com.muslimedu.attendance.data.remote.dto.RosterData
import com.muslimedu.attendance.data.remote.dto.RosterRequest
import com.muslimedu.attendance.data.remote.dto.TeacherClassesData
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("login")
    suspend fun login(@Body request: LoginRequest): ApiEnvelope<LoginData>

    @POST("me")
    suspend fun me(): ApiEnvelope<MeData>

    @POST("refresh-token")
    suspend fun refreshToken(): ApiEnvelope<RefreshTokenData>

    @POST("logout")
    suspend fun logout(): ApiEnvelope<Unit>

    @POST("teacher_attendance_classes")
    suspend fun teacherAttendanceClasses(): ApiEnvelope<TeacherClassesData>

    @POST("teacher_attendance_roster")
    suspend fun teacherAttendanceRoster(@Body request: RosterRequest): ApiEnvelope<RosterData>

    /**
     * The batch "take attendance" / finalize-the-roster call - locks the
     * whole (section, subject, date) roster on its first successful call
     * for that combination (see [AttendanceSubmitRequest]'s doc comment).
     * Not used for individual RFID scans - see [teacherAttendanceScan].
     */
    @POST("teacher_attendance_submit")
    suspend fun submitAttendance(@Body request: AttendanceSubmitRequest): ApiEnvelope<AttendanceSubmitData>

    /**
     * One check-in per call, never locks the roster - see
     * [AttendanceScanRequest]'s doc comment. This is what [com.muslimedu.attendance.sync.SyncQueueManager]
     * actually calls for each RFID-triggered scan.
     */
    @POST("teacher_attendance_scan")
    suspend fun scanAttendance(@Body request: AttendanceScanRequest): ApiEnvelope<AttendanceScanData>

    /**
     * Admin-only school directory browsing (Classes -> Sections -> Students),
     * separate from the teacher-scoped roster sync above - see
     * [AdminClassSummaryDto][com.muslimedu.attendance.data.remote.dto.AdminClassSummaryDto]'s
     * file-level doc comment for why these three are read-only and never
     * write into the same [com.muslimedu.attendance.data.db.dao.StudentDao]
     * cache the scan flow uses. Gates on `role_id === 2` server-side -
     * stricter than this app's own admin/superadmin check.
     */
    // No default value on the @Body param here - Retrofit implements this
    // interface with a dynamic proxy, not real Kotlin bytecode, so a Kotlin
    // default parameter's compiler-generated call site would have no
    // synthetic method on that proxy to dispatch to. Callers always pass
    // AdminClassesListRequest() explicitly instead.
    @POST("admin_classes_list")
    suspend fun adminClassesList(@Body request: AdminClassesListRequest): ApiEnvelope<AdminClassesListData>

    @POST("admin_sections_list")
    suspend fun adminSectionsList(@Body request: AdminSectionsListRequest): ApiEnvelope<AdminSectionsListData>

    @POST("admin_section_students")
    suspend fun adminSectionStudents(@Body request: AdminSectionStudentsRequest): ApiEnvelope<AdminSectionStudentsData>

    /**
     * Admin-only campus-wide gate in/out attendance - resolves the student by
     * `code` across the WHOLE school (any active student), unlike
     * [scanAttendance] which only matches a student enrolled in the caller's
     * section. See [GateAttendanceScanRequest]'s doc comment.
     */
    @POST("admin_gate_attendance_scan")
    suspend fun adminGateAttendanceScan(@Body request: GateAttendanceScanRequest): ApiEnvelope<GateAttendanceScanData>

    /** Every gate scan recorded school-wide for the requested day - the "who's on/off campus" list. */
    @POST("admin_gate_attendance_today")
    suspend fun adminGateAttendanceToday(@Body request: GateAttendanceTodayRequest): ApiEnvelope<GateAttendanceTodayData>
}
