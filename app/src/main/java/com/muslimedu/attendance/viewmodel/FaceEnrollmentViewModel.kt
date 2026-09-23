package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.FaceEnrollResult
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class EnrollmentUiState {
    data object SelectingStudent : EnrollmentUiState()
    data class Capturing(val student: StudentEntity) : EnrollmentUiState()
    data class Success(val student: StudentEntity, val livenessScore: Float) : EnrollmentUiState()
    data class Failed(val student: StudentEntity, val reason: String) : EnrollmentUiState()
}

@HiltViewModel
class FaceEnrollmentViewModel @Inject constructor(
    private val studentRepository: StudentRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val sessionManager: SessionManager,
    private val photoCache: StudentPhotoCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EnrollmentUiState>(EnrollmentUiState.SelectingStudent)
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    private val _students = MutableStateFlow<List<StudentEntity>>(emptyList())
    val students: StateFlow<List<StudentEntity>> = _students.asStateFlow()

    init {
        // Re-read on every session change - see RfidEnrollmentViewModel's init.
        viewModelScope.launch {
            sessionManager.currentUser.collect {
                _students.value = studentRepository.getAll()
            }
        }
    }

    fun selectStudent(student: StudentEntity) {
        _uiState.value = EnrollmentUiState.Capturing(student)
    }

    fun onCaptured(bitmap: Bitmap) {
        val state = _uiState.value
        if (state !is EnrollmentUiState.Capturing) return
        viewModelScope.launch {
            val result = faceTemplateRepository.enroll(
                schoolId = state.student.schoolId,
                studentId = state.student.studentId,
                bitmap = bitmap,
                enrolledBy = sessionManager.currentUser.value?.email,
            )
            _uiState.value = when (result) {
                is FaceEnrollResult.Success -> EnrollmentUiState.Success(state.student, result.livenessScore)
                is FaceEnrollResult.NoFaceDetected -> EnrollmentUiState.Failed(state.student, "No face detected - try again")
                is FaceEnrollResult.LivenessTooLow -> EnrollmentUiState.Failed(
                    state.student,
                    "Liveness check failed (score %.2f) - try again in better lighting".format(result.score),
                )
            }
        }
    }

    fun reset() {
        _uiState.value = EnrollmentUiState.SelectingStudent
    }

    /** The student's locally-cached photo, if one has been downloaded - never fetches over the network itself. */
    suspend fun loadPhoto(student: StudentEntity): Bitmap? =
        photoCache.loadCachedBitmap(student.schoolId, student.studentId)
}
