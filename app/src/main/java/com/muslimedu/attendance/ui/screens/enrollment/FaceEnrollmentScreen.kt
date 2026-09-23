package com.muslimedu.attendance.ui.screens.enrollment

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.ui.components.LiveFaceCaptureView
import com.muslimedu.attendance.ui.components.StepIndicator
import com.muslimedu.attendance.viewmodel.EnrollmentUiState
import com.muslimedu.attendance.viewmodel.FaceEnrollmentViewModel

private val FACE_ENROLLMENT_STEPS = listOf("Select Student", "Capture", "Confirm")

/**
 * [presetTarget] jumps straight into capture for one student (e.g. tapped from
 * the admin Student List's per-row face icon), skipping the picker. Pass a
 * fresh [PresetFaceTarget] (a new [PresetFaceTarget.requestId]) each time, even
 * for the same student - otherwise a second tap while this screen is already
 * showing that student's result does nothing, since Compose skips a
 * [LaunchedEffect] whose key didn't change.
 */
data class PresetFaceTarget(val student: StudentEntity, val requestId: Long)

@Composable
fun FaceEnrollmentScreen(
    presetTarget: PresetFaceTarget? = null,
    viewModel: FaceEnrollmentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val students by viewModel.students.collectAsState()

    // Keyed on requestId (null for the generic "Enroll a Student's Face" entry
    // point) so this always re-runs on a fresh visit to the screen: either
    // jump straight to capture for the preset student, or reset - the
    // ViewModel outlives this composable (no back stack backs it), so without
    // the reset branch a leftover Success/Failed from a previous visit would
    // still be showing instead of the picker.
    LaunchedEffect(presetTarget?.requestId) {
        if (presetTarget != null) viewModel.selectStudent(presetTarget.student) else viewModel.reset()
    }

    val currentStep = when (uiState) {
        is EnrollmentUiState.SelectingStudent -> 1
        is EnrollmentUiState.Capturing -> 2
        is EnrollmentUiState.Success, is EnrollmentUiState.Failed -> 3
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StepIndicator(
                steps = FACE_ENROLLMENT_STEPS,
                currentStep = currentStep,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            when (val state = uiState) {
                is EnrollmentUiState.SelectingStudent ->
                    StudentPickerContent(
                        students,
                        title = "Enroll a Student's Face",
                        onSelect = viewModel::selectStudent,
                        loadPhoto = viewModel::loadPhoto,
                    )
                is EnrollmentUiState.Capturing -> CaptureContent(state.student, onCaptured = viewModel::onCaptured)
                is EnrollmentUiState.Success -> ResultContent(
                    title = "Enrolled",
                    message = "%s - liveness score %.2f".format(state.student.name, state.livenessScore),
                    isError = false,
                    onDone = viewModel::reset,
                    buttonLabel = "Enroll Another",
                )
                is EnrollmentUiState.Failed -> ResultContent(
                    title = "Enrollment Failed",
                    message = state.reason,
                    isError = true,
                    onDone = viewModel::reset,
                    buttonLabel = "Try Again",
                )
            }
        }
    }
}

@Composable
private fun CaptureContent(student: StudentEntity, onCaptured: (Bitmap) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Capture Face", style = MaterialTheme.typography.titleLarge)
        Text(student.name, modifier = Modifier.padding(bottom = 16.dp))
        LiveFaceCaptureView(onCaptured = onCaptured, modifier = Modifier.fillMaxWidth())
    }
}
