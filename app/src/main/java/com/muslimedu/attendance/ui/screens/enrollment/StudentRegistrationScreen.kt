package com.muslimedu.attendance.ui.screens.enrollment

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.components.EmptyState
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.components.LiveFaceCaptureView
import com.muslimedu.attendance.ui.components.StepIndicator
import com.muslimedu.attendance.ui.screens.gate.CheckMark
import com.muslimedu.attendance.ui.screens.gate.ReaderLine
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.RegisteredCard
import com.muslimedu.attendance.viewmodel.RegistrationCandidate
import com.muslimedu.attendance.viewmodel.RegistrationTarget
import com.muslimedu.attendance.viewmodel.RegistrationUiState
import com.muslimedu.attendance.viewmodel.StudentRegistrationViewModel

private val REGISTRATION_STEPS = listOf("Student", "Card", "Face", "Done")

/**
 * Register Card & Face - one wizard: pick a student, tap their card, then
 * enroll their face. [requestId] must be new for each visit (and survive
 * activity recreation) - see [StudentRegistrationViewModel.enter].
 */
@Composable
fun StudentRegistrationScreen(
    target: RegistrationTarget?,
    requestId: Long,
    onFinish: () -> Unit,
    viewModel: StudentRegistrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val candidates by viewModel.candidates.collectAsState()

    DisposableEffect(requestId) {
        viewModel.enter(requestId, target)
        onDispose { viewModel.leave() }
    }

    val step = when (uiState) {
        is RegistrationUiState.SelectingStudent -> 1
        is RegistrationUiState.TapCard, is RegistrationUiState.ConfirmReplace -> 2
        is RegistrationUiState.Face -> 3
        is RegistrationUiState.Done -> 4
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StepIndicator(
            steps = REGISTRATION_STEPS,
            currentStep = step,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (val state = uiState) {
            is RegistrationUiState.SelectingStudent ->
                StudentPicker(candidates, onSelect = viewModel::selectStudent, loadPhoto = viewModel::loadPhoto)
            is RegistrationUiState.TapCard -> {
                val readerStatus by viewModel.readerStatus.collectAsState()
                StepPage {
                    TapCardStep(state, readerStatus, onKeepCard = viewModel::keepCurrentCard)
                }
            }
            is RegistrationUiState.ConfirmReplace -> StepPage {
                ConfirmReplaceStep(state, onReplace = viewModel::confirmReplace, onCancel = viewModel::cancelReplace)
            }
            is RegistrationUiState.Face -> StepPage {
                FaceStep(
                    state,
                    onCaptured = viewModel::onFaceCaptured,
                    onCapture = viewModel::captureFace,
                    onSkip = viewModel::skipFace,
                )
            }
            is RegistrationUiState.Done -> StepPage {
                DoneStep(state, onNext = viewModel::reset, onFinish = onFinish)
            }
        }
    }
}

/** Scrollable, centred column the steps after the picker share. */
@Composable
private fun StepPage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun StudentPicker(
    candidates: List<RegistrationCandidate>,
    onSelect: (StudentEntity) -> Unit,
    loadPhoto: suspend (StudentEntity) -> Bitmap?,
) {
    var query by remember { mutableStateOf("") }
    // A focused text field keeps key events away from the card reader (see
    // MainActivity), so let go of the search box before the card step.
    val focusManager = LocalFocusManager.current
    val shown = remember(candidates, query) {
        candidates.filter {
            query.isBlank() || it.student.name.contains(query, ignoreCase = true) || it.student.code.contains(query, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Pick a student, tap their RFID card, then enroll their face.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search name or Student ID") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        when {
            candidates.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "No students yet",
                message = "Download them in Sync & Account first.",
                modifier = Modifier.padding(top = 32.dp),
            )
            shown.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "No matching students",
                modifier = Modifier.padding(top = 32.dp),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                items(shown, key = { it.student.id }) { candidate ->
                    PickerRow(
                        candidate,
                        onClick = {
                            focusManager.clearFocus()
                            onSelect(candidate.student)
                        },
                        loadPhoto = { loadPhoto(candidate.student) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(candidate: RegistrationCandidate, onClick: () -> Unit, loadPhoto: suspend () -> Bitmap?) {
    val student = candidate.student
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto() }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StudentAvatar(student.name, photo)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(student.name, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull("ID ${student.code}", student.sectionName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckMark(if (student.rfidCardNumber != null) "Card" else "No card", student.rfidCardNumber != null)
                    CheckMark(if (candidate.hasFace) "Face" else "No face", candidate.hasFace)
                }
            }
        }
    }
}

@Composable
private fun StudentAvatar(name: String, photo: Bitmap?, size: Dp = 44.dp) {
    if (photo != null) {
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        InitialsAvatar(name, BrandPrimary, size = size)
    }
}

/** Name, Student ID and section - the same header on every step. */
@Composable
private fun StudentHeader(student: StudentEntity) {
    InitialsAvatar(student.name, BrandPrimary, size = 56.dp)
    Text(
        student.name,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        listOfNotNull("Student ID ${student.code}", student.sectionName).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Waits for the physical card - there is no typed-in UID, so every registered card really exists. */
@Composable
private fun TapCardStep(state: RegistrationUiState.TapCard, readerStatus: ReaderStatus, onKeepCard: () -> Unit) {
    val student = state.student
    StudentHeader(student)
    Icon(
        Icons.Filled.CreditCard,
        contentDescription = null,
        tint = BrandPrimary,
        modifier = Modifier.padding(top = 24.dp).size(64.dp),
    )
    Text(
        "Tap ${student.name}'s card on the reader",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    CircularProgressIndicator(color = BrandPrimary, modifier = Modifier.padding(top = 16.dp).size(28.dp))
    ReaderLine(readerStatus, modifier = Modifier.padding(top = 16.dp))
    state.error?.let {
        Text(it, color = AccentRed, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
    student.rfidCardNumber?.let { current ->
        Text(
            "Current card: $current. Tap a different card to replace it, or keep this one.",
            style = MaterialTheme.typography.bodySmall,
            color = AccentGold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        OutlinedButton(onClick = onKeepCard, modifier = Modifier.padding(top = 8.dp)) {
            Text("Keep this card - next: face")
        }
    }
}

@Composable
private fun ConfirmReplaceStep(state: RegistrationUiState.ConfirmReplace, onReplace: () -> Unit, onCancel: () -> Unit) {
    StudentHeader(state.student)
    Text(
        "Replace card?",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp),
    )
    Text(
        "${state.student.name} already has card ${state.oldUid}. Registering card ${state.newUid} " +
            "deactivates the old one - it will no longer work at the gate.",
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(modifier = Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = onReplace) { Text("Replace card") }
    }
}

/** The card saved at step 2, shown above the face and done steps. */
@Composable
private fun CardSummary(card: RegisteredCard) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandPrimary)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                val what = when {
                    card.kept -> "kept"
                    card.replacedUid != null -> "registered (old card ${card.replacedUid} deactivated)"
                    else -> "registered"
                }
                Text("RFID card ${card.uid} $what", fontWeight = FontWeight.Medium)
                Text(card.serverNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FaceStep(
    state: RegistrationUiState.Face,
    onCaptured: (Bitmap) -> Unit,
    onCapture: () -> Unit,
    onSkip: () -> Unit,
) {
    StudentHeader(state.student)
    CardSummary(state.card)
    Text(
        if (state.hasFace) "Face" else "Next: enroll the face",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 20.dp),
    )
    when {
        state.saving -> {
            CircularProgressIndicator(color = BrandPrimary, modifier = Modifier.padding(top = 24.dp))
            Text("Saving face...", modifier = Modifier.padding(top = 12.dp))
        }
        state.capturing -> {
            Text(
                "Look straight at the camera - it captures automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // A fresh camera per attempt: the capture view fires once.
            key(state.attempt) {
                LiveFaceCaptureView(
                    onCaptured = onCaptured,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(MaterialTheme.shapes.medium),
                )
            }
            TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (state.hasFace) "Keep current face" else "Skip face for now")
            }
        }
        state.error != null -> {
            Text(state.error, color = AccentRed, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Button(onClick = onCapture, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
            TextButton(onClick = onSkip) {
                Text(if (state.hasFace) "Keep current face" else "Skip face for now")
            }
        }
        else -> {
            Text(
                "${state.student.name} already has a face enrolled on this device.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCapture) { Text("Re-enroll face") }
                Button(onClick = onSkip) { Text("Keep current face") }
            }
        }
    }
}

@Composable
private fun DoneStep(state: RegistrationUiState.Done, onNext: () -> Unit, onFinish: () -> Unit) {
    StudentHeader(state.student)
    CardSummary(state.card)
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            CheckMark(if (state.faceEnrolled) "Face enrolled" else "No face enrolled", state.faceEnrolled)
            if (!state.faceEnrolled) {
                Text(
                    "The gate refuses this student until a face is enrolled - open them again from Students.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("Register next student") }
    OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Finish") }
}
