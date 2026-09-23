package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Add-student dialog state; null hides the dialog. */
data class AddStudentState(
    val isSaving: Boolean = false,
    val error: String? = null,
)

/** One row in the list, with whether a face template is on file - never the template itself. */
data class StudentRow(val student: StudentEntity, val hasFace: Boolean)

@HiltViewModel
class StudentListViewModel @Inject constructor(
    private val studentRepository: StudentRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val photoCache: StudentPhotoCache,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _rows = MutableStateFlow<List<StudentRow>>(emptyList())
    val rows: StateFlow<List<StudentRow>> = _rows.asStateFlow()

    private val _isAddingStudent = MutableStateFlow(false)
    val isAddingStudent: StateFlow<Boolean> = _isAddingStudent.asStateFlow()

    private val _addState = MutableStateFlow(AddStudentState())
    val addState: StateFlow<AddStudentState> = _addState.asStateFlow()

    init {
        // Reloads on session change - see TeacherDashboardViewModel's init.
        // StudentRepository.getAll() is scoped to the logged-in account's
        // school, so this also drops the previous account's students.
        viewModelScope.launch {
            sessionManager.currentUser.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val students = studentRepository.getAll()
            val enrolledKeys = faceTemplateRepository.enrolledKeys()
            _rows.value = students.map { student ->
                StudentRow(student, hasFace = (student.schoolId to student.studentId) in enrolledKeys)
            }
        }
    }

    /** The student's locally-cached photo, if one has been downloaded - never fetches over the network itself. */
    suspend fun loadPhoto(student: StudentEntity): Bitmap? =
        photoCache.loadCachedBitmap(student.schoolId, student.studentId)

    fun openAddStudentDialog() {
        _addState.value = AddStudentState()
        _isAddingStudent.value = true
    }

    fun dismissAddStudentDialog() {
        _isAddingStudent.value = false
    }

    fun addStudent(name: String, code: String, sectionName: String?) {
        if (name.isBlank() || code.isBlank()) {
            _addState.value = AddStudentState(error = "Name and code are required")
            return
        }
        viewModelScope.launch {
            _addState.value = AddStudentState(isSaving = true)
            studentRepository.addLocalStudent(name.trim(), code.trim(), sectionName?.trim()?.ifBlank { null })
                .onSuccess {
                    _isAddingStudent.value = false
                    _addState.value = AddStudentState()
                    refresh()
                }
                .onFailure { e -> _addState.value = AddStudentState(error = e.message ?: "Could not add student") }
        }
    }
}
