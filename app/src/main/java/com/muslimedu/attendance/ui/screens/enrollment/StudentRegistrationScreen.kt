package com.muslimedu.attendance.ui.screens.enrollment

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.muslimedu.attendance.ui.theme.BrandTeal
import com.muslimedu.attendance.util.SmsTemplate
import com.muslimedu.attendance.util.formatPhMobile
import com.muslimedu.attendance.util.normalizePhMobile
import com.muslimedu.attendance.viewmodel.RegisteredCard
import com.muslimedu.attendance.viewmodel.RegisteredPhone
import com.muslimedu.attendance.viewmodel.RegistrationCandidate
import com.muslimedu.attendance.viewmodel.RegistrationTarget
import com.muslimedu.attendance.viewmodel.RegistrationUiState
import com.muslimedu.attendance.viewmodel.StudentRegistrationViewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val REGISTRATION_STEPS = listOf("Student", "Card", "Face", "Parent no.", "Done")

/**
 * Register Card, Face & Number - a wizard with one full page per step:
 * pick a student, tap their card, enroll their face, add the parent's
 * mobile number. Every step moves on by itself: a card read goes straight
 * to the face, the face is captured automatically on the gate's
 * full-screen camera, a complete number saves itself, and Done moves on to
 * the next student. What a step already has (a card, a face, a number) is
 * kept after a short countdown. [requestId] must be new for each visit (and
 * survive activity recreation) - see [StudentRegistrationViewModel.enter].
 *
 * Draws its own header (the face step needs the whole screen), so [onBack]
 * leaves the wizard.
 */
@Composable
fun StudentRegistrationScreen(
    target: RegistrationTarget?,
    requestId: Long,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    viewModel: StudentRegistrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val candidates by viewModel.candidates.collectAsState()

    DisposableEffect(requestId) {
        viewModel.enter(requestId, target)
        onDispose { viewModel.leave() }
    }

    // Opened on one student from the Students list: back and Done return there.
    // Opened from Admin: back from a step returns to the picker.
    val openedForStudent = target != null
    val back = {
        if (!openedForStudent && uiState !is RegistrationUiState.SelectingStudent) viewModel.reset() else onBack()
    }
    BackHandler(onBack = back)

    val state = uiState
    if (state is RegistrationUiState.Face && (state.capturing || state.saving)) {
        FullScreenFaceCapture(
            state,
            onCaptured = viewModel::onFaceCaptured,
            onSkip = viewModel::skipFace,
            loadPhoto = viewModel::loadPhoto,
        )
        return
    }

    val step = when (state) {
        is RegistrationUiState.SelectingStudent -> 1
        is RegistrationUiState.TapCard -> 2
        is RegistrationUiState.Face -> 3
        is RegistrationUiState.ParentPhone -> 4
        is RegistrationUiState.Done -> 5
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WizardHeader(step, onBack = back)
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(240)) { -it / 3 } + fadeOut(tween(200)))
            },
            label = "wizardStep",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                is RegistrationUiState.SelectingStudent ->
                    StudentPicker(candidates, onSelect = viewModel::selectStudent, loadPhoto = viewModel::loadPhoto)
                is RegistrationUiState.TapCard -> {
                    val readerStatus by viewModel.readerStatus.collectAsState()
                    StepPage(page.student, viewModel::loadPhoto) {
                        TapCardStep(page, readerStatus, onKeepCard = viewModel::keepCurrentCard)
                    }
                }
                is RegistrationUiState.Face -> StepPage(page.student, viewModel::loadPhoto) {
                    FaceStatusStep(page, onCapture = viewModel::captureFace, onSkip = viewModel::skipFace)
                }
                is RegistrationUiState.ParentPhone -> StepPage(page.student, viewModel::loadPhoto) {
                    ParentPhoneStep(page, onSave = viewModel::saveParentPhone, onSkip = viewModel::skipParentPhone)
                }
                is RegistrationUiState.Done -> StepPage(page.student, viewModel::loadPhoto) {
                    DoneStep(
                        page,
                        openedForStudent = openedForStudent,
                        onNext = viewModel::reset,
                        onFinish = onFinish,
                    )
                }
            }
        }
    }
}

/** Back, the title with "Step 2 of 5", and the step circles. */
@Composable
private fun WizardHeader(step: Int, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Column {
                Text("Register Student", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Step $step of ${REGISTRATION_STEPS.size} · ${REGISTRATION_STEPS[step - 1]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StepIndicator(
            steps = REGISTRATION_STEPS,
            currentStep = step,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** One step's own page: who it's for at the top, then the step, centred and scrollable. */
@Composable
private fun StepPage(student: StudentEntity, loadPhoto: suspend (StudentEntity) -> Bitmap?, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StudentChip(student, loadPhoto)
            content()
        }
    }
}

@Composable
private fun StudentChip(student: StudentEntity, loadPhoto: suspend (StudentEntity) -> Bitmap?) {
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto(student) }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StudentAvatar(student.name, photo, size = 36.dp)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(student.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    listOfNotNull("ID ${student.code}", student.sectionName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A big icon in soft rings - pulsing while the step waits for something (a card). */
@Composable
private fun StepHero(icon: ImageVector, color: Color, pulsing: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "hero")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 1.18f else 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier = Modifier.padding(top = 28.dp).size(150.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(150.dp).scale(pulse).background(color.copy(alpha = 0.08f), CircleShape))
        Box(modifier = Modifier.size(112.dp).background(color.copy(alpha = 0.14f), CircleShape))
        Box(modifier = Modifier.size(78.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String?) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp),
    )
    subtitle?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A bar that runs down over [millis] and then calls [onDone] - how a step
 * says "moving on by itself". Restarts when [key] changes.
 */
@Composable
private fun CountdownBar(millis: Long, label: String, color: Color, key: Any, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val done by rememberUpdatedState(onDone)
    val remaining = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        remaining.animateTo(0f, tween(millis.toInt(), easing = LinearEasing))
        done()
    }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        LinearProgressIndicator(
            progress = { remaining.value },
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

// ── Step 1: student ──────────────────────────────────────────────────

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
        Text("Who are you registering?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Tap a student. The next steps - card, face and parent's number - move on by themselves.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search name or Student ID") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
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
                // Same labels either way - the tick or cross says which, and keeps three fitting on a phone.
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CheckMark("Card", student.rfidCardNumber != null)
                    CheckMark("Face", candidate.hasFace)
                    CheckMark("Parent no.", student.parentPhone != null)
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
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        InitialsAvatar(name, BrandPrimary, size = size)
    }
}

// ── Step 2: card ─────────────────────────────────────────────────────

/** Waits for the physical card - there is no typed-in UID, so every registered card really exists. */
@Composable
private fun TapCardStep(state: RegistrationUiState.TapCard, readerStatus: ReaderStatus, onKeepCard: () -> Unit) {
    val student = state.student
    StepHero(Icons.Filled.CreditCard, BrandPrimary, pulsing = true)
    StepTitle(
        "Tap the card on the reader",
        "Hold ${student.name}'s RFID card to the reader - it moves on to the face by itself.",
    )
    ReaderLine(readerStatus, modifier = Modifier.padding(top = 16.dp))
    state.error?.let {
        Surface(color = AccentRed.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 16.dp)) {
            Text(it, color = AccentRed, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
        }
    }
    student.rfidCardNumber?.let { current ->
        Surface(
            color = AccentGold.copy(alpha = 0.1f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Already has card $current. Tap a new card to replace it.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                CountdownBar(
                    millis = StudentRegistrationViewModel.KEEP_MILLIS,
                    label = "Keeping the current card",
                    color = AccentGold,
                    key = student.id to state.error,
                    onDone = onKeepCard,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = onKeepCard) { Text("Keep it now") }
            }
        }
    }
}

// ── Step 3: face ─────────────────────────────────────────────────────

/**
 * The face capture, full screen like the gate's face check: the camera
 * under the oval guide and scan animation, who it's for at the top, and one
 * control - skip. A try that doesn't take re-arms the same camera by itself.
 */
@Composable
private fun FullScreenFaceCapture(
    state: RegistrationUiState.Face,
    onCaptured: (Bitmap) -> Unit,
    onSkip: () -> Unit,
    loadPhoto: suspend (StudentEntity) -> Bitmap?,
) {
    val student = state.student
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto(student) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LiveFaceCaptureView(
            onCaptured = onCaptured,
            modifier = Modifier.fillMaxSize(),
            fullScreen = true,
            captureKey = state.attempt,
            message = if (state.saving) "Saving face..." else state.hint,
            busy = state.saving,
            accent = BrandTeal,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onSkip, shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                Icon(Icons.Filled.Close, contentDescription = "Skip face", tint = Color.White, modifier = Modifier.padding(10.dp))
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cached = photo
                    if (cached != null) {
                        Image(
                            bitmap = cached.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(36.dp).clip(CircleShape),
                        )
                    } else {
                        InitialsAvatar(student.name, BrandTeal, size = 36.dp)
                    }
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(student.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "Step 3 of ${REGISTRATION_STEPS.size} · Face enrollment",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The face step when the camera isn't up: a face already enrolled (kept by
 * itself after a countdown, or re-enrolled), or a face that belongs to
 * another student - the one case that needs the admin's choice.
 */
@Composable
private fun FaceStatusStep(state: RegistrationUiState.Face, onCapture: () -> Unit, onSkip: () -> Unit) {
    if (state.error != null) {
        StepHero(Icons.Filled.Warning, AccentRed)
        StepTitle("Face not saved", state.error)
        Button(onClick = onCapture, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("Try again") }
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Skip face for now") }
        return
    }
    StepHero(Icons.Filled.Face, BrandPrimary)
    StepTitle("Face already enrolled", "${state.student.name} already has a face on this device.")
    CountdownBar(
        millis = StudentRegistrationViewModel.KEEP_MILLIS,
        label = "Keeping it - next: parent's number",
        color = BrandPrimary,
        key = state.student.id,
        onDone = onSkip,
        modifier = Modifier.padding(top = 28.dp),
    )
    TextButton(onClick = onCapture, modifier = Modifier.padding(top = 8.dp)) { Text("Re-enroll face instead") }
}

// ── Step 4: parent's number ──────────────────────────────────────────

/**
 * The parent's mobile number - where the gate texts go. Saves by itself a
 * moment after a complete Philippine mobile number is typed; a number the
 * student already has is kept after a countdown unless the admin edits it.
 * Shows the kind of text the parent will get.
 */
@Composable
private fun ParentPhoneStep(
    state: RegistrationUiState.ParentPhone,
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val student = state.student
    val current = student.parentPhone
    var input by rememberSaveable(student.id) { mutableStateOf(current?.let(::formatPhMobile).orEmpty()) }
    var edited by rememberSaveable(student.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val normalized = normalizePhMobile(input)
    val isNew = normalized != null && normalized != current

    // A complete new number saves itself after a short pause - no Save button.
    LaunchedEffect(input) {
        if (isNew) {
            delay(StudentRegistrationViewModel.PHONE_SAVE_DELAY_MILLIS)
            focusManager.clearFocus()
            onSave(input)
        }
    }

    StepHero(Icons.Filled.Sms, BrandPrimary)
    StepTitle(
        "Parent's mobile number",
        "A text goes to this number each time ${student.name} scans Coming In or Going Out.",
    )
    if (student.hasParentAccount == false) {
        Text(
            "The school server has no parent account linked to ${student.name} yet - link one on the web. " +
                "A number saved here waits on this device until then.",
            style = MaterialTheme.typography.bodySmall,
            color = AccentGold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    if (student.phoneSyncStatus == StudentEntity.RFID_FAILED) {
        Text(
            "The school server refused the last number: ${student.phoneSyncError ?: "unknown reason"}",
            style = MaterialTheme.typography.bodySmall,
            color = AccentRed,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    OutlinedTextField(
        value = input,
        onValueChange = { typed ->
            edited = true
            input = typed.filter { it.isDigit() || it in " +-()" }.take(20)
        },
        label = { Text("Mobile number") },
        placeholder = { Text("0917 123 4567") },
        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
        trailingIcon = {
            if (normalized != null) Icon(Icons.Filled.CheckCircle, contentDescription = "Valid number", tint = BrandPrimary)
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        isError = state.error != null,
        supportingText = {
            Text(
                state.error ?: when {
                    normalized != null && isNew -> "Saving ${formatPhMobile(normalized)}..."
                    normalized != null -> "Texts go to ${formatPhMobile(normalized)}"
                    input.isBlank() -> "Philippine mobile number, 11 digits"
                    else -> "Keep typing - 11 digits starting with 09"
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    )

    ParentTextPreview(student)

    when {
        current != null && !edited -> {
            CountdownBar(
                millis = StudentRegistrationViewModel.KEEP_MILLIS,
                label = "Keeping ${formatPhMobile(current)}",
                color = BrandPrimary,
                key = student.id,
                onDone = onSkip,
                modifier = Modifier.padding(top = 20.dp),
            )
            TextButton(onClick = onSkip) { Text("Keep it now") }
        }
        input.isBlank() && current != null -> TextButton(onClick = { onSave("") }, modifier = Modifier.padding(top = 12.dp)) {
            Text("Remove the number", color = AccentRed)
        }
        !isNew -> TextButton(onClick = onSkip, modifier = Modifier.padding(top = 12.dp)) {
            Text(if (current != null) "Keep ${formatPhMobile(current)}" else "Skip for now")
        }
    }
}

/** What the parent will receive - the default wording (the school's own is set in Admin > Parent SMS). */
@Composable
private fun ParentTextPreview(student: StudentEntity) {
    val now = remember { LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) }
    val text = SmsTemplate.render(SmsTemplate.DEFAULT_IN, student.name, student.code, now, LocalDate.now().toString(), null)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            "The parent gets a text like this:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Sms, contentDescription = null, tint = BrandPrimary, modifier = Modifier.padding(top = 6.dp).size(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ── Step 5: done ─────────────────────────────────────────────────────

private data class SummaryItem(val icon: ImageVector, val ok: Boolean, val title: String, val note: String?)

private fun cardItem(card: RegisteredCard?): SummaryItem = if (card == null) {
    SummaryItem(Icons.Filled.CreditCard, false, "No RFID card", "The gate can't identify this student until a card is registered.")
} else {
    val what = when {
        card.kept -> "kept"
        card.replacedUid != null -> "registered (old card ${card.replacedUid} deactivated)"
        else -> "registered"
    }
    SummaryItem(Icons.Filled.CreditCard, true, "RFID card ${card.uid} $what", card.serverNote)
}

private fun faceItem(enrolled: Boolean): SummaryItem = if (enrolled) {
    SummaryItem(Icons.Filled.Face, true, "Face enrolled", null)
} else {
    SummaryItem(Icons.Filled.Face, false, "No face enrolled", "The gate refuses this student until a face is enrolled.")
}

private fun phoneItem(phone: RegisteredPhone): SummaryItem = if (phone.phone == null) {
    SummaryItem(Icons.Filled.Sms, false, "No parent number", "The parent gets no gate texts. Add one later: Admin > Students.")
} else {
    SummaryItem(
        Icons.Filled.Sms,
        true,
        "Parent ${formatPhMobile(phone.phone)}" + if (phone.changed) " saved" else "",
        phone.serverNote,
    )
}

@Composable
private fun DoneStep(state: RegistrationUiState.Done, openedForStudent: Boolean, onNext: () -> Unit, onFinish: () -> Unit) {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
    Box(modifier = Modifier.padding(top = 28.dp).scale(pop.value), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(120.dp).background(BrandPrimary.copy(alpha = 0.12f), CircleShape))
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandPrimary, modifier = Modifier.size(84.dp))
    }
    StepTitle("Registered!", "${state.student.name} is ready for the gate.")

    val items = listOf(cardItem(state.card), faceItem(state.faceEnrolled), phoneItem(state.phone))
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            items.forEach { item ->
                val color = if (item.ok) BrandPrimary else AccentRed
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(item.title, fontWeight = FontWeight.Medium, color = if (item.ok) Color.Unspecified else AccentRed)
                        item.note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    CountdownBar(
        millis = StudentRegistrationViewModel.DONE_MILLIS,
        label = if (openedForStudent) "Back to Students" else "Next student",
        color = BrandPrimary,
        key = state.student.id,
        onDone = if (openedForStudent) onFinish else onNext,
        modifier = Modifier.padding(top = 24.dp),
    )
    TextButton(onClick = onFinish, modifier = Modifier.padding(top = 4.dp)) { Text("Finish") }
}
