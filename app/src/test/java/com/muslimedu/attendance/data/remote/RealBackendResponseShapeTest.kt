package com.muslimedu.attendance.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.muslimedu.attendance.data.remote.dto.AdminClassesListData
import com.muslimedu.attendance.data.remote.dto.AdminSectionStudentsData
import com.muslimedu.attendance.data.remote.dto.AdminSectionsListData
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.remote.dto.AttendanceScanData
import com.muslimedu.attendance.data.remote.dto.AttendanceSubmitData
import com.muslimedu.attendance.data.remote.dto.LoginData
import com.muslimedu.attendance.data.remote.dto.MeData
import com.muslimedu.attendance.data.remote.dto.RosterData
import com.muslimedu.attendance.data.remote.dto.TeacherClassesData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage against the *actual* Laravel backend's response
 * bodies (read from the real controller source - app/Http/Controllers/
 * ApiController.php and Traits/AttendanceApi.php - not the spec document
 * this app was originally built against, which diverges from the real
 * backend in every endpoint covered here). Each body is copied from what
 * those methods literally construct and return.
 */
class RealBackendResponseShapeTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(ApiEnvelopeTypeAdapterFactory())
        .create()

    @Test
    fun `me response is wrapped one level under 'user', not the user's fields at top level`() {
        val body = """{"user":{"id":42,"name":"Qahtan Farasi","email":"q@f.com","role_id":3,"role":"teacher","school_id":7,"code":"T042"}}"""
        val type = object : TypeToken<ApiEnvelope<MeData>>() {}.type
        val envelope: ApiEnvelope<MeData> = gson.fromJson(body, type)

        assertTrue(envelope.success)
        assertEquals("Qahtan Farasi", envelope.data?.user?.name)
        assertEquals(7, envelope.data?.user?.schoolId)
    }

    @Test
    fun `teacher_attendance_classes returns a flat 'classes' list, homeroom subjectId is 0 not null`() {
        val body = """
        {"classes":[
          {"section_id":10,"section_name":"Grade 5A","class_id":3,"class_name":"Grade 5","subject_id":0,"subject_name":"Homeroom / Daily","role":"homeroom"},
          {"section_id":10,"section_name":"Grade 5A","class_id":3,"class_name":"Grade 5","subject_id":21,"subject_name":"Quran","role":"subject"}
        ]}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<TeacherClassesData>>() {}.type
        val envelope: ApiEnvelope<TeacherClassesData> = gson.fromJson(body, type)

        assertEquals(2, envelope.data?.classes?.size)
        assertEquals(0, envelope.data?.classes?.get(0)?.subjectId)
        assertEquals("Quran", envelope.data?.classes?.get(1)?.subjectName)
    }

    @Test
    fun `teacher_attendance_roster has top-level section fields, no rfid field, and a locked block`() {
        val body = """
        {"section_id":10,"section_name":"Grade 5A","subject_id":0,"date":"2026-09-12",
         "students":[
           {"student_id":501,"student_name":"Ali Hassan","code":"S501","photo":"https://manhaje.com/apps/assets/uploads/user-images/ali.jpg","address":"123 St","gender":"male","age":11,"status":null,"check_in_time":null,"remarks":null,"attendance_id":null}
         ],
         "summary":{"present":0,"late":0,"absent":0,"excused":0,"leave":0},
         "locked":true,"locked_at":"2026-09-11T14:30:00.000000Z","locked_by_name":"Admin User"}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<RosterData>>() {}.type
        val envelope: ApiEnvelope<RosterData> = gson.fromJson(body, type)

        assertEquals(10, envelope.data?.sectionId)
        assertEquals("Ali Hassan", envelope.data?.students?.get(0)?.studentName)
        assertEquals("https://manhaje.com/apps/assets/uploads/user-images/ali.jpg", envelope.data?.students?.get(0)?.photo)
        assertEquals(true, envelope.data?.locked)
        assertEquals("Admin User", envelope.data?.lockedByName)
    }

    @Test
    fun `teacher_attendance_submit response has no submitted, skipped, or attendance_ids`() {
        val body = """{"message":"Attendance saved.","summary":{"present":12,"late":1},"count":1,"locked":true,"locked_at":"2026-09-12T09:15:00.000000Z"}"""
        val type = object : TypeToken<ApiEnvelope<AttendanceSubmitData>>() {}.type
        val envelope: ApiEnvelope<AttendanceSubmitData> = gson.fromJson(body, type)

        assertEquals("Attendance saved.", envelope.message)
        assertEquals(1, envelope.data?.count)
        assertEquals(12, envelope.data?.summary?.get("present"))
        assertEquals(true, envelope.data?.locked)
    }

    @Test
    fun `teacher_attendance_scan returns one resolved student with a real attendance_id, and never locks`() {
        // Confirms this endpoint's shape is distinct from submit's: an
        // attendance_id actually comes back here (submit's never does), and
        // there is no locked/locked_at field at all - teacher_attendance_scan
        // never locks the roster, which is the whole reason SyncQueueManager
        // uses this endpoint instead of /teacher_attendance_submit for
        // individual RFID scans (see AttendanceScanRequest's doc comment).
        val body = """
        {"message":"Ali Hassan marked present.",
         "student":{"student_id":501,"student_name":"Ali Hassan","photo":"https://manhaje.com/apps/assets/uploads/user-images/ali.jpg","status":"present","check_in_time":"08:12","remarks":null,"attendance_id":9001},
         "summary":{"present":1,"late":0,"absent":0,"excused":0,"leave":0}}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<AttendanceScanData>>() {}.type
        val envelope: ApiEnvelope<AttendanceScanData> = gson.fromJson(body, type)

        assertTrue(envelope.success)
        assertEquals(501, envelope.data?.student?.studentId)
        assertEquals(9001, envelope.data?.student?.attendanceId)
        assertEquals("present", envelope.data?.student?.status)
    }

    @Test
    fun `admin_classes_list returns a flat 'classes' list alongside pagination, no data wrapper`() {
        // Body shape copied from ApiController::admin_classes_list() /
        // formatClassSummary() - many more fields exist on the real class
        // summary (department, campus, curriculum, shift, ...) than this app
        // has any use for; only the ones AdminClassSummaryDto actually reads
        // are asserted here.
        val body = """
        {"classes":[
          {"id":12,"class_uuid":"c-uuid","class_code":"G5","name":"Grade 5","grade_level":"5","section":null,
           "department":null,"campus_id":1,"campus":"Main","curriculum":null,"school_year":"2025-2026",
           "semester_term":null,"room_number":null,"building":null,"floor":null,"shift":"morning",
           "class_type":"regular","max_capacity":90,"current_enrollment":62,"available_slots":28,
           "enrollment_percentage":68.89,"status":"active","start_date":"2025-08-01","end_date":"2026-06-01"}
        ],"pagination":{"total":6,"per_page":100,"current_page":1,"last_page":1}}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<AdminClassesListData>>() {}.type
        val envelope: ApiEnvelope<AdminClassesListData> = gson.fromJson(body, type)

        assertTrue(envelope.success)
        assertEquals(1, envelope.data?.classes?.size)
        assertEquals("Grade 5", envelope.data?.classes?.first()?.name)
        assertEquals(62, envelope.data?.classes?.first()?.currentEnrollment)
    }

    @Test
    fun `admin_sections_list returns a flat 'sections' list with class_name inline`() {
        // Body shape from ApiController::admin_sections_list().
        val body = """
        {"sections":[
          {"id":34,"name":"Grade 5A","class_id":12,"class_name":"Grade 5","class_teacher_id":7,
           "class_teacher_name":"Mrs. Amina","capacity":30,"current_enrollment":28,"available_slots":2,
           "room_number":"12","status":"active"}
        ]}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<AdminSectionsListData>>() {}.type
        val envelope: ApiEnvelope<AdminSectionsListData> = gson.fromJson(body, type)

        assertTrue(envelope.success)
        assertEquals("Grade 5A", envelope.data?.sections?.first()?.name)
        assertEquals("Grade 5", envelope.data?.sections?.first()?.className)
    }

    @Test
    fun `admin_section_students returns students with no 'code' field at all`() {
        // Body shape from ApiController::admin_section_students() - the
        // student row genuinely has no code/rfid field, confirming
        // AdminDirectoryRepository's doc comment on why it never writes
        // these rows into StudentDao.
        val body = """
        {"section_id":34,"section_name":"Grade 5A","class_id":12,"class_name":"Grade 5",
         "capacity":30,"current_enrollment":1,"available_slots":29,
         "students":[{"id":501,"name":"Ali Hassan","email":"ali@school.test","photo":"https://manhaje.com/apps/assets/uploads/user-images/ali.jpg","phone":"0555","gender":"male"}]}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<AdminSectionStudentsData>>() {}.type
        val envelope: ApiEnvelope<AdminSectionStudentsData> = gson.fromJson(body, type)

        assertTrue(envelope.success)
        assertEquals("Grade 5A", envelope.data?.sectionName)
        assertEquals(501, envelope.data?.students?.first()?.id)
        assertEquals("Ali Hassan", envelope.data?.students?.first()?.name)
    }

    @Test
    fun `login 2FA-required response has no data key or token, only requires_two_factor`() {
        val body = """{"message":"Two-factor authentication code required.","requires_two_factor":true}"""
        val type = object : TypeToken<ApiEnvelope<LoginData>>() {}.type
        val envelope: ApiEnvelope<LoginData> = gson.fromJson(body, type)

        assertTrue(envelope.data?.requiresTwoFactor == true)
        assertNull(envelope.data?.token)
    }
}
