package com.muslimedu.attendance.ui.screens.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.ui.components.LiveFaceCaptureView
import com.muslimedu.attendance.ui.components.NetworkStatusBadge
import com.muslimedu.attendance.ui.components.ReaderStatusBadge
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.AccentTealContainer
import com.muslimedu.attendance.ui.theme.BrandPurpleContainer
import com.muslimedu.attendance.ui.theme.TextPrimary
import com.muslimedu.attendance.viewmodel.RfidViewModel
import com.muslimedu.attendance.viewmodel.ScanUiState
import kotlinx.coroutines.delay

@Composable
fun RfidScanScreen(
    isAdmin: Boolean = false,
    onAssignToStudent: (String) -> Unit = {},
    viewModel: RfidViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val readerStatus by viewModel.readerStatus.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    // Full-bleed dark background while actively scanning, matching the design
    // canvas - the state cards below keep their own light teal/gold/red/purple
    // containers unchanged, they just now sit on a dark backdrop instead of a
    // plain white one.
    Surface(modifier = Modifier.fillMaxSize(), color = TextPrimary, contentColor = Color.White) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ReaderStatusBadge(status = readerStatus)
            NetworkStatusBadge(isOnline = isOnline, modifier = Modifier.padding(top = 8.dp))

            Box(modifier = Modifier.padding(top = 32.dp)) {
                when (val state = uiState) {
                    is ScanUiState.Listening -> ListeningCard()
                    is ScanUiState.Matched -> MatchedStudentCard(state.student, state.uid, onDismiss = viewModel::dismissResult)
                    is ScanUiState.DuplicateScan -> DuplicateScanCard(state.student, state.existingCheckInTime, onDismiss = viewModel::dismissResult)
                    is ScanUiState.AwaitingFaceVerification -> FaceVerificationCard(
                        student = state.student,
                        failureReason = null,
                        onCaptured = viewModel::onFaceCaptured,
                    )
                    is ScanUiState.FaceVerificationFailed -> FaceVerificationCard(
                        student = state.student,
                        failureReason = state.reason,
                        onCaptured = viewModel::onFaceCaptured,
                    )
                    is ScanUiState.VerifyingFace -> VerifyingFaceCard()
                    is ScanUiState.UnknownCard -> UnknownCardCard(
                        uid = state.uid,
                        showAssignAction = isAdmin,
                        onDismiss = viewModel::dismissResult,
                        onAssignToStudent = {
                            viewModel.dismissResult()
                            onAssignToStudent(state.uid)
                        },
                    )
                    is ScanUiState.ReaderError -> ReaderErrorCard(state.message, onDismiss = viewModel::dismissResult)
                }
            }

            if (viewModel.canSimulate) {
                Button(
                    onClick = { viewModel.simulateScan() },
                    modifier = Modifier.padding(top = 32.dp),
                ) {
                    Text("Simulate Scan (debug)")
                }
            }
        }
    }
}

@Composable
private fun ListeningCard() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.CreditCard,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Scan RFID Card",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(text = "Tap a student's card on the reader")
    }
}

/** Auto-returns to the scan-ready state a couple seconds after a successful match, so the teacher doesn't have to tap "Scan Next" between students. */
@Composable
private fun MatchedStudentCard(student: StudentEntity, uid: String, onDismiss: () -> Unit) {
    LaunchedEffect(student.id, uid) {
        delay(2000)
        onDismiss()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = AccentTealContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AccentTeal,
            )
            Text(student.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(student.code)
            student.sectionName?.let { Text(it) }
            Text(text = "UID: $uid", modifier = Modifier.padding(top = 8.dp))
            Text(
                text = "Present - recorded",
                color = AccentTeal,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("Scan Next")
            }
        }
    }
}

@Composable
private fun DuplicateScanCard(student: StudentEntity, existingCheckInTime: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentGoldContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AccentGold,
            )
            Text("Already Marked Present", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(student.name)
            Text(text = "Checked in at $existingCheckInTime today")
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("OK")
            }
        }
    }
}

/**
 * Auto-captures via [LiveFaceCaptureView] - an embedded live preview, not the
 * system camera app - as soon as it sees a plausibly live face for a few
 * consecutive frames. No shutter tap and no manual skip: a failed attempt
 * (see [failureReason]) just re-arms the camera for another try.
 */
@Composable
private fun FaceVerificationCard(
    student: StudentEntity,
    failureReason: String?,
    onCaptured: (Bitmap) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failureReason != null) AccentRedContainer else BrandPurpleContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Facial Detection", style = MaterialTheme.typography.titleLarge)
            Text(student.name)
            if (failureReason != null) {
                Text(text = failureReason, color = AccentRed, modifier = Modifier.padding(top = 8.dp))
            }
            LiveFaceCaptureView(
                onCaptured = onCaptured,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun VerifyingFaceCard() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(text = "Verifying face...", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun UnknownCardCard(
    uid: String,
    showAssignAction: Boolean,
    onDismiss: () -> Unit,
    onAssignToStudent: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentGoldContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AccentGold,
            )
            Text("Unknown Card", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(text = "UID: $uid")
            Text("This card is not assigned to any student.")
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("Dismiss")
            }
            // Admin-only: jumps straight into RFID Enrollment's student
            // picker with this UID ready to assign, instead of the admin
            // having to remember and re-type it once they get there.
            if (showAssignAction) {
                OutlinedButton(onClick = onAssignToStudent, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Assign to Student")
                }
            }
        }
    }
}

@Composable
private fun ReaderErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentRedContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AccentRed,
            )
            Text("Reader Error", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(message)
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("Dismiss")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MatchedPreview() {
    MatchedStudentCard(
        student = StudentEntity(
            schoolId = 1, studentId = 1, name = "Mohammed Ahmed", code = "STU001",
            sectionName = "Grade 5A", rfidCardNumber = "04:1A:2B:3C",
            createdAt = 0, updatedAt = 0,
        ),
        uid = "04:1A:2B:3C",
        onDismiss = {},
    )
}
