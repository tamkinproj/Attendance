package com.muslimedu.attendance.ui.screens.enrollment

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.ui.components.StepIndicator
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSuccess
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.PresetRfidTarget
import com.muslimedu.attendance.viewmodel.PresetRfidUid
import com.muslimedu.attendance.viewmodel.RfidEnrollmentUiState
import com.muslimedu.attendance.viewmodel.RfidEnrollmentViewModel

private val RFID_ENROLLMENT_STEPS = listOf("Select Student", "Tap Card", "Confirm")

@Composable
fun RfidEnrollmentScreen(
    presetTarget: PresetRfidTarget? = null,
    presetUid: PresetRfidUid? = null,
    viewModel: RfidEnrollmentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val students by viewModel.students.collectAsState()

    // Same reasoning as FaceEnrollmentScreen's LaunchedEffect: this ViewModel
    // outlives navigating away and back (no back stack backs this screen),
    // so a fresh visit must always either jump to the preset student or
    // reset - otherwise a leftover Success/Failed from a previous visit
    // would still be showing instead of the picker. A preset UID (no student
    // chosen yet) still needs the picker, so it resets the same as no preset
    // at all - the UID itself is consumed below, once a student is picked.
    LaunchedEffect(presetTarget?.requestId, presetUid?.requestId) {
        if (presetTarget != null) viewModel.selectStudent(presetTarget.student) else viewModel.reset()
    }

    val currentStep = when (uiState) {
        is RfidEnrollmentUiState.SelectingStudent -> 1
        is RfidEnrollmentUiState.Listening -> 2
        is RfidEnrollmentUiState.Success, is RfidEnrollmentUiState.Failed -> 3
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StepIndicator(
                steps = RFID_ENROLLMENT_STEPS,
                currentStep = currentStep,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            when (val state = uiState) {
                is RfidEnrollmentUiState.SelectingStudent -> {
                    if (presetUid != null) {
                        Text(
                            text = "Card UID ${presetUid.uid} ready to assign – pick who it belongs to",
                            color = BrandPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                        )
                    }
                    StudentPickerContent(
                        students,
                        title = "Assign an RFID Card",
                        onSelect = { student ->
                            if (presetUid != null) viewModel.assignManually(student, presetUid.uid) else viewModel.selectStudent(student)
                        },
                        loadPhoto = viewModel::loadPhoto,
                    )
                }
                is RfidEnrollmentUiState.Listening -> ListeningContent(
                    student = state.student,
                    canSimulate = viewModel.canSimulate,
                    onSimulateScan = viewModel::simulateScan,
                    onManualAssign = { uid -> viewModel.assignManually(state.student, uid) },
                )
                is RfidEnrollmentUiState.Success -> ResultContent(
                    title = "Card Assigned",
                    message = "${state.student.name} -> ${state.uid}",
                    isError = false,
                    onDone = viewModel::reset,
                )
                is RfidEnrollmentUiState.Failed -> ResultContent(
                    title = "Assignment Failed",
                    message = state.reason,
                    isError = true,
                    onDone = viewModel::reset,
                )
            }
        }
    }
}

@Composable
private fun ListeningContent(
    student: StudentEntity,
    canSimulate: Boolean,
    onSimulateScan: () -> Unit,
    onManualAssign: (String) -> Unit,
) {
    var manualUid by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Tap ${student.name}'s card on the reader",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )

        if (canSimulate) {
            Button(onClick = onSimulateScan, modifier = Modifier.padding(top = 24.dp)) {
                Text("Simulate Scan (debug)")
            }
        }

        Text(text = "Or enter the UID manually", modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(
            value = manualUid,
            onValueChange = { manualUid = it },
            label = { Text("Card UID") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Button(
            onClick = { if (manualUid.isNotBlank()) onManualAssign(manualUid.trim()) },
            enabled = manualUid.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Assign")
        }
    }
}

@Composable
internal fun StudentPickerContent(
    students: List<StudentEntity>,
    title: String,
    onSelect: (StudentEntity) -> Unit,
    loadPhoto: suspend (StudentEntity) -> Bitmap? = { null },
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("Select a student from the synced roster", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(students) { student ->
                StudentPickerRow(student, onClick = { onSelect(student) }, loadPhoto = { loadPhoto(student) })
            }
        }
    }
}

@Composable
private fun StudentPickerRow(
    student: StudentEntity,
    onClick: () -> Unit,
    loadPhoto: suspend () -> Bitmap?,
) {
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto() }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cachedPhoto = photo
            if (cachedPhoto != null) {
                Image(
                    bitmap = cachedPhoto.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(student.name, fontWeight = FontWeight.Medium)
                Text(student.code, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ResultContent(
    title: String,
    message: String,
    isError: Boolean,
    onDone: () -> Unit,
    buttonLabel: String = "Done",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text = message, color = if (isError) AccentRed else AccentSuccess)
        Button(onClick = onDone, modifier = Modifier.padding(top = 24.dp)) {
            Text(buttonLabel)
        }
    }
}
