package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.remote.dto.AdminClassSummaryDto
import com.muslimedu.attendance.data.remote.dto.AdminSectionDto
import com.muslimedu.attendance.data.remote.dto.AdminSectionStudentDto
import com.muslimedu.attendance.data.repository.AdminDirectoryRepository
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One backend student row, joined against this device's *local* knowledge of
 * them: [localEntity] is non-null only if this student is already in
 * [StudentDao] from a real teacher roster sync (i.e. has a real `code` this
 * app could use), which is what gates whether the RFID/face actions in
 * [AdminDirectoryUiState.StudentDetail] are enabled at all - see
 * [AdminDirectoryRepository]'s doc comment for why a student who only shows
 * up via this admin directory can't just be inserted into that cache.
 */
data class AdminDirectoryStudentRow(
    val student: AdminSectionStudentDto,
    val localEntity: StudentEntity?,
    val hasFaceTemplate: Boolean,
)

sealed class AdminDirectoryUiState {
    data object LoadingClasses : AdminDirectoryUiState()
    data class ClassesError(val message: String) : AdminDirectoryUiState()
    data class Classes(val classes: List<AdminClassSummaryDto>) : AdminDirectoryUiState()

    data class LoadingSections(val className: String) : AdminDirectoryUiState()
    data class SectionsError(val className: String, val message: String) : AdminDirectoryUiState()
    data class Sections(val className: String, val sections: List<AdminSectionDto>) : AdminDirectoryUiState()

    data class LoadingStudents(val sectionName: String) : AdminDirectoryUiState()
    data class StudentsError(val sectionName: String, val message: String) : AdminDirectoryUiState()
    data class Students(val sectionName: String, val rows: List<AdminDirectoryStudentRow>) : AdminDirectoryUiState()

    data class StudentDetail(val sectionName: String, val row: AdminDirectoryStudentRow) : AdminDirectoryUiState()
}

@HiltViewModel
class AdminDirectoryViewModel @Inject constructor(
    private val repository: AdminDirectoryRepository,
    private val studentDao: StudentDao,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminDirectoryUiState>(AdminDirectoryUiState.LoadingClasses)
    val uiState: StateFlow<AdminDirectoryUiState> = _uiState.asStateFlow()

    // Remembered only to support "back" from Students to Sections without a
    // second round trip - this screen has no back stack of its own beyond
    // that one level (see AdminDirectoryScreen's doc comment for why the
    // whole Classes/Sections/Students/Detail flow is one composable rather
    // than four AppRoot destinations).
    private var lastClassName: String = ""
    private var lastSections: List<AdminSectionDto> = emptyList()
    private var lastSectionName: String = ""
    private var lastStudentRows: List<AdminDirectoryStudentRow> = emptyList()

    init {
        loadClasses()
    }

    fun loadClasses() {
        viewModelScope.launch {
            _uiState.value = AdminDirectoryUiState.LoadingClasses
            repository.fetchClasses()
                .onSuccess { classes -> _uiState.value = AdminDirectoryUiState.Classes(classes) }
                .onFailure { e -> _uiState.value = AdminDirectoryUiState.ClassesError(e.message ?: "Could not load classes") }
        }
    }

    fun selectClass(schoolClass: AdminClassSummaryDto) {
        viewModelScope.launch {
            _uiState.value = AdminDirectoryUiState.LoadingSections(schoolClass.name)
            repository.fetchSections(schoolClass.id)
                .onSuccess { sections ->
                    lastClassName = schoolClass.name
                    lastSections = sections
                    when {
                        // Matches the same "skip the picker if there's only
                        // one choice" convention RosterViewModel.loadClasses
                        // already uses for a teacher's own classes.
                        sections.size == 1 -> selectSection(sections.first())
                        sections.isEmpty() -> _uiState.value = AdminDirectoryUiState.SectionsError(
                            schoolClass.name, "This class has no sections yet",
                        )
                        else -> _uiState.value = AdminDirectoryUiState.Sections(schoolClass.name, sections)
                    }
                }
                .onFailure { e ->
                    _uiState.value = AdminDirectoryUiState.SectionsError(schoolClass.name, e.message ?: "Could not load sections")
                }
        }
    }

    fun selectSection(section: AdminSectionDto) {
        viewModelScope.launch {
            _uiState.value = AdminDirectoryUiState.LoadingStudents(section.name)
            val schoolId = sessionManager.currentUser.value?.schoolId
            repository.fetchSectionStudents(section.id)
                .onSuccess { students ->
                    lastSectionName = section.name
                    val enrolledKeys = faceTemplateRepository.enrolledKeys()
                    val rows = students.map { student ->
                        val local = schoolId?.let { studentDao.findBySchoolAndStudentId(it, student.id) }
                        AdminDirectoryStudentRow(
                            student = student,
                            localEntity = local,
                            hasFaceTemplate = schoolId != null && (schoolId to student.id) in enrolledKeys,
                        )
                    }
                    lastStudentRows = rows
                    _uiState.value = AdminDirectoryUiState.Students(section.name, rows)
                }
                .onFailure { e ->
                    _uiState.value = AdminDirectoryUiState.StudentsError(section.name, e.message ?: "Could not load students")
                }
        }
    }

    fun selectStudent(row: AdminDirectoryStudentRow) {
        _uiState.value = AdminDirectoryUiState.StudentDetail(lastSectionName, row)
    }

    /** Re-checks local RFID/face status for the current student without a network round trip - called after returning from assigning a card or enrolling a face. */
    fun refreshCurrentStudent() {
        val state = _uiState.value
        if (state !is AdminDirectoryUiState.StudentDetail) return
        viewModelScope.launch {
            val schoolId = sessionManager.currentUser.value?.schoolId ?: return@launch
            val studentId = state.row.student.id
            val local = studentDao.findBySchoolAndStudentId(schoolId, studentId)
            val hasFace = (schoolId to studentId) in faceTemplateRepository.enrolledKeys()
            val updatedRow = state.row.copy(localEntity = local, hasFaceTemplate = hasFace)
            lastStudentRows = lastStudentRows.map { if (it.student.id == studentId) updatedRow else it }
            _uiState.value = state.copy(row = updatedRow)
        }
    }

    /** Back from Student Detail to the Students list. */
    fun backToStudents() {
        _uiState.value = AdminDirectoryUiState.Students(lastSectionName, lastStudentRows)
    }

    /** Back from Students (or a Sections/Students error) to the Sections list, or to Classes if this class only had one section (so Sections was skipped on the way in). */
    fun backToSections() {
        if (lastSections.size <= 1) {
            loadClasses()
        } else {
            _uiState.value = AdminDirectoryUiState.Sections(lastClassName, lastSections)
        }
    }

    /** Back from Sections to Classes. */
    fun backToClasses() {
        loadClasses()
    }
}
