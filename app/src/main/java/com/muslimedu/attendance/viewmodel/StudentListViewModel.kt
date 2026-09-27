package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.sync.GateSyncScheduler
import com.muslimedu.attendance.sync.RfidCardSyncManager
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

/** One row in the list, with how many face angles are on file (0 = no face) - never the templates themselves. */
data class StudentRow(val student: StudentEntity, val faceAngles: Int) {
    val hasFace: Boolean get() = faceAngles > 0
}

@HiltViewModel
class StudentListViewModel @Inject constructor(
    private val studentRepository: StudentRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val photoCache: StudentPhotoCache,
    private val deviceSettings: DeviceSettings,
    private val rfidCardSyncManager: RfidCardSyncManager,
    private val gateSyncScheduler: GateSyncScheduler,
) : ViewModel() {

    private val _rows = MutableStateFlow<List<StudentRow>>(emptyList())
    val rows: StateFlow<List<StudentRow>> = _rows.asStateFlow()

    private val _isAddingStudent = MutableStateFlow(false)
    val isAddingStudent: StateFlow<Boolean> = _isAddingStudent.asStateFlow()

    private val _addState = MutableStateFlow(AddStudentState())
    val addState: StateFlow<AddStudentState> = _addState.asStateFlow()

    init {
        // Reloads when the device gets linked to a school - getAll() is
        // scoped to it, and linking moves hand-added students onto it.
        viewModelScope.launch {
            deviceSettings.schoolId.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val students = studentRepository.getAll()
            val angles = faceTemplateRepository.enrolledAngles()
            _rows.value = students.map { student ->
                StudentRow(student, faceAngles = angles[student.schoolId to student.studentId] ?: 0)
            }
        }
    }

    /**
     * Deactivates the student's card (lost, broken, or moving to someone
     * else) - here at once, on the server as soon as it can be sent.
     */
    fun deactivateCard(student: StudentEntity) {
        viewModelScope.launch {
            studentRepository.deactivateRfidCard(student)
            refresh()
            gateSyncScheduler.syncWhenOnline()
            rfidCardSyncManager.flush()
            refresh()
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
