package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Admin-only, read-only "browse the school" endpoints - confirmed against the
 * real backend (`ApiController::admin_classes_list/admin_sections_list/
 * admin_section_students`), separate from the teacher-scoped
 * `/teacher_attendance_classes` + `/teacher_attendance_roster` this app
 * already uses for the actual scan flow. Two things make that separation
 * necessary, not just tidier:
 *
 * - These three endpoints gate on `role_id === 2` (`requireAdmin()`)
 *   specifically - **not** "admin or superadmin" the way this app's own
 *   [com.muslimedu.attendance.ui.navigation.AppRoot] gate does. A
 *   superadmin can see the Admin Dashboard in this app but will get a 403
 *   from all three of these if they tap in to browse.
 * - `admin_section_students`' student rows have no `code` field at all
 *   (unlike `/teacher_attendance_roster`'s, which does) - `code` is what
 *   [com.muslimedu.attendance.data.db.entities.StudentEntity] requires
 *   (non-null, unique-indexed) and what `/teacher_attendance_scan` actually
 *   sends to identify a student server-side. A student browsed here who
 *   isn't already in the local roster cache genuinely has no `code` this
 *   app could store for them, so this repository never writes into
 *   `StudentDao` - RFID/face actions in the student detail screen only
 *   light up for a student who's already locally cached from a real
 *   `/teacher_attendance_roster` sync.
 */
/**
 * `per_page` is the only field this app sets - capped server-side at 100
 * (`min((int) ($request->per_page ?: 15), 100)`), well above what a school's
 * class list realistically needs for a first pass with no search/filter UI.
 * Every other filter/sort field `admin_classes_list` accepts is left unset
 * rather than built here.
 */
data class AdminClassesListRequest(
    @SerializedName("per_page") val perPage: Int = 100,
)

data class AdminSectionsListRequest(
    @SerializedName("class_id") val classId: Int,
)

data class AdminSectionStudentsRequest(
    @SerializedName("section_id") val sectionId: Int,
)

data class AdminClassSummaryDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("grade_level") val gradeLevel: String?,
    @SerializedName("current_enrollment") val currentEnrollment: Int,
)

data class AdminClassesListData(
    @SerializedName("classes") val classes: List<AdminClassSummaryDto>?,
)

data class AdminSectionDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("class_id") val classId: Int,
    @SerializedName("class_name") val className: String?,
    @SerializedName("current_enrollment") val currentEnrollment: Int,
)

data class AdminSectionsListData(
    @SerializedName("sections") val sections: List<AdminSectionDto>?,
)

/** No `code` field - see this file's top-level doc comment for why that matters. */
data class AdminSectionStudentDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String?,
    @SerializedName("photo") val photo: String?,
    @SerializedName("phone") val phone: String?,
    @SerializedName("gender") val gender: String?,
)

data class AdminSectionStudentsData(
    @SerializedName("section_id") val sectionId: Int,
    @SerializedName("section_name") val sectionName: String?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("students") val students: List<AdminSectionStudentDto>?,
)
