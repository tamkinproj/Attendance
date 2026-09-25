package com.muslimedu.attendance.ui.screens.gate

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FaceRetouchingOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.repository.GateScanCheck
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.components.LiveFaceCaptureView
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.GateScanState
import com.muslimedu.attendance.viewmodel.GateScanViewModel
import com.muslimedu.attendance.viewmodel.LeaveDialogState
import com.muslimedu.attendance.viewmodel.SessionResult
import java.time.LocalDate

/**
 * "RFID Coming In" / "RFID Going Out". The student taps their card, is
 * identified (step 1), confirms their face with the existing live face
 * check (step 2), and only then is the attendance recorded. See
 * [GateScanViewModel] for the rules.
 *
 * Draws its own header (tinted by direction) instead of the app bar, and
 * owns back: leaving with attendance not yet synced asks first.
 */
@Composable
fun GateScanScreen(
    direction: GateDirection,
    onClose: () -> Unit,
    viewModel: GateScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val leaveDialog by viewModel.leaveDialog.collectAsState()
    val leaveApproved by viewModel.leaveApproved.collectAsState()
    val unsynced by viewModel.unsyncedCount.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val readerStatus by viewModel.readerStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    val schedule by viewModel.schedule.collectAsState()

    LaunchedEffect(direction) { viewModel.enter(direction) }
    DisposableEffect(Unit) { onDispose { viewModel.exit() } }
    LaunchedEffect(leaveApproved) {
        if (leaveApproved) {
            viewModel.consumeLeave()
            onClose()
        }
    }
    BackHandler { viewModel.requestLeave() }

    val accent = direction.color()
    val current = state

    if (current is GateScanState.FaceCheck) {
        // Step 2 takes the whole screen: the camera, the oval guide and the scan animation.
        FullScreenFaceStep(current, direction, accent, viewModel::loadPhoto, viewModel::onFaceCaptured, viewModel::cancelCheck)
    } else {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Header(direction, accent, unsynced, isSyncing, readerStatus, schedule?.perDay, onBack = viewModel::requestLeave)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val content = Modifier.fillMaxWidth().widthIn(max = 560.dp)
                // No buttons on any result: each one goes back to "tap your card" by itself
                // (a tap closes it sooner).
                when (current) {
                    GateScanState.Ready -> {
                        ReadyCard(direction, accent, content)
                        SessionLog(session, content)
                    }
                    is GateScanState.FaceCheck -> Unit
                    is GateScanState.Recorded -> SuccessCard(current, accent, viewModel::loadPhoto, viewModel::dismissResult, content)
                    is GateScanState.NotAllowed -> {
                        val (title, message) = notAllowedText(current)
                        NoticeCard(
                            title = title,
                            message = message,
                            color = if (current.check is GateScanCheck.SameAsLast) directionColor(current.direction.apiValue) else AccentGold,
                            icon = if (current.check is GateScanCheck.SameAsLast) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                            onTap = viewModel::dismissResult,
                            modifier = content,
                        )
                    }
                    is GateScanState.FaceFailed -> {
                        StudentCard(current.student, viewModel::loadPhoto, content, faceFailed = true)
                        NoticeCard(
                            title = "Face Confirmation Failed",
                            message = "Attendance was not recorded.\n${current.reason}",
                            color = AccentRed,
                            icon = Icons.Filled.FaceRetouchingOff,
                            onTap = viewModel::dismissResult,
                            modifier = content,
                        )
                    }
                    is GateScanState.NoFaceEnrolled -> {
                        StudentCard(current.student, viewModel::loadPhoto, content, faceFailed = true)
                        NoticeCard(
                            title = "Face Confirmation Failed",
                            message = "Attendance was not recorded.\n${current.student.name} has no face enrolled on this device. " +
                                "An admin can enroll it in Admin > Register Card & Face.",
                            color = AccentRed,
                            icon = Icons.Filled.FaceRetouchingOff,
                            onTap = viewModel::dismissResult,
                            modifier = content,
                        )
                    }
                    is GateScanState.UnknownCard -> NoticeCard(
                        title = "Card not registered",
                        message = "Attendance was not recorded.\nCard ${current.uid} isn't registered to any student on this device. " +
                            "An admin can register it in Admin > Register Card & Face.",
                        color = AccentRed,
                        icon = Icons.Filled.CreditCard,
                        onTap = viewModel::dismissResult,
                        modifier = content,
                    )
                }
            }
        }
    }

    LeaveDialog(
        state = leaveDialog,
        onSaveAndSync = viewModel::saveAndSync,
        onLeave = viewModel::leaveWithoutSyncing,
        onCancel = viewModel::cancelLeave,
        onAcknowledge = viewModel::acknowledgeNotFinished,
    )
}

@Composable
private fun Header(
    direction: GateDirection,
    accent: Color,
    unsynced: Int,
    isSyncing: Boolean,
    readerStatus: ReaderStatus,
    scansPerDay: Int?,
    onBack: () -> Unit,
) {
    Surface(color = accent, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "RFID ${direction.label}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(displayDate(LocalDate.now().toString()), scansPerDay?.let { "$it In · $it Out per day" })
                            .joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isSyncing || unsynced > 0) {
                    Surface(color = Color.White.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(
                                if (isSyncing) "Synchronizing" else "$unsynced pending sync",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            ReaderLine(readerStatus, onDark = true, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
    }
}

@Composable
private fun ReadyCard(direction: GateDirection, accent: Color, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(132.dp).background(accent.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(92.dp).background(accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Nfc, contentDescription = null, tint = accent, modifier = Modifier.size(52.dp))
                }
            }
            Text(
                "Tap your RFID card/tag on the reader.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                "Then look at the camera to confirm your face. ${direction.label} is recorded only after both checks pass.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Step 1 - RFID identification: who the card belongs to. */
@Composable
private fun StudentCard(
    student: StudentEntity,
    loadPhoto: suspend (StudentEntity) -> Bitmap?,
    modifier: Modifier,
    faceFailed: Boolean = false,
) {
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto(student) }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            StepLabel(1, "RFID Identification")
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val cached = photo
                if (cached != null) {
                    Image(
                        bitmap = cached.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                } else {
                    InitialsAvatar(student.name, BrandPrimary, size = 64.dp)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Student: ${student.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Student ID: ${student.code}", style = MaterialTheme.typography.bodyMedium)
                    Text("Section: ${student.sectionName ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CheckMark("RFID Verified", ok = true)
                if (faceFailed) CheckMark("Face not confirmed", ok = false)
            }
        }
    }
}

@Composable
private fun StepLabel(number: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(24.dp).background(BrandPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "Step $number - $title",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Step 2 - the existing live face check (auto-capture, no manual shutter). */
/**
 * Step 2 - face confirmation, full screen: the live camera under the oval
 * guide and scan animation, with who is being checked at the top. Retries
 * happen by themselves on the same camera; the only control is Cancel.
 */
@Composable
private fun FullScreenFaceStep(
    check: GateScanState.FaceCheck,
    direction: GateDirection,
    accent: Color,
    loadPhoto: suspend (StudentEntity) -> Bitmap?,
    onCaptured: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val student = check.student
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto(student) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LiveFaceCaptureView(
            onCaptured = onCaptured,
            modifier = Modifier.fillMaxSize(),
            fullScreen = true,
            captureKey = check.attempt,
            message = if (check.checking) "Checking face..." else check.hint,
            busy = check.checking,
            accent = accent,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(onClick = onCancel, shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.padding(10.dp))
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
                        InitialsAvatar(student.name, accent, size = 36.dp)
                    }
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(student.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "ID ${student.code} · ${direction.label} · RFID ✓",
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
 * The confirmation, laid out like the mockup: tick, photo, name, then
 * Student ID / section and today's time in / time out. Closes by itself.
 */
@Composable
private fun SuccessCard(
    recorded: GateScanState.Recorded,
    accent: Color,
    loadPhoto: suspend (StudentEntity) -> Bitmap?,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val scan = recorded.scan
    val student = recorded.student
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto(student) }
    Card(
        onClick = onTap,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandPrimary, modifier = Modifier.size(56.dp))
            Text(
                "Attendance Recorded",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = BrandPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "RFID card and face confirmed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val cached = photo
            if (cached != null) {
                Image(
                    bitmap = cached.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(top = 18.dp).size(96.dp).clip(CircleShape),
                )
            } else {
                InitialsAvatar(student.name, accent, modifier = Modifier.padding(top = 18.dp), size = 96.dp)
            }
            Text(
                student.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            SmallChip("${directionLabel(scan.direction)} · ${recorded.number} of ${recorded.perDay} today", accent, Modifier.padding(top = 8.dp))

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth()) {
                InfoCell("Student ID", scan.studentCode, accent, Modifier.weight(1f))
                InfoCell("Coming In", recorded.timeIn?.let(::displayTime) ?: "—", accent, Modifier.weight(1f), alignEnd = true)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                InfoCell("Section", scan.sectionName ?: "—", accent, Modifier.weight(1f))
                InfoCell("Going Out", recorded.timeOut?.let(::displayTime) ?: "—", accent, Modifier.weight(1f), alignEnd = true)
            }
            AutoCloseBar(accent, Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, accent: Color, modifier: Modifier, alignEnd: Boolean = false) {
    Column(modifier = modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = accent)
    }
}

/** Shows the result closing itself - there is no button to press. */
@Composable
private fun AutoCloseBar(color: Color, modifier: Modifier = Modifier) {
    val remaining = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        remaining.animateTo(0f, tween(GateScanViewModel.AUTO_CLOSE_MILLIS.toInt(), easing = LinearEasing))
    }
    LinearProgressIndicator(
        progress = { remaining.value },
        color = color,
        trackColor = color.copy(alpha = 0.15f),
        modifier = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
    )
}

/** Title and message for a scan the gate schedule refused. */
private fun notAllowedText(s: GateScanState.NotAllowed): Pair<String, String> {
    val name = s.student.name
    val dir = s.direction.label
    return when (val check = s.check) {
        is GateScanCheck.SameAsLast -> "Already $dir" to
            "Attendance was not recorded again.\n$name was recorded $dir at ${displayTime(check.last.scanTime)}. " +
            "Their next scan is ${s.direction.opposite.label}."
        is GateScanCheck.NotOpenYet -> "$dir ${check.number} not open yet" to
            "Attendance was not recorded.\n$dir ${check.number} opens at ${displayTime(check.opensAt.toString())}. " +
            "$name can scan from then."
        is GateScanCheck.LimitReached -> "No more $dir today" to
            "Attendance was not recorded.\n$name already has all ${check.perDay} $dir scan(s) for today " +
            "(last at ${displayTime(check.last.scanTime)}). This gate is set to ${check.perDay} In and ${check.perDay} Out per day."
        GateScanCheck.NotSetUp -> "Gate not set up" to
            "Attendance was not recorded.\nAn admin must first set how many Coming In and Going Out scans each student " +
            "makes per day (Admin > Gate Schedule)."
        is GateScanCheck.Allowed -> "" to ""
    }
}

@Composable
private fun NoticeCard(
    title: String,
    message: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    Card(onClick = onTap, modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text(message, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            AutoCloseBar(color, Modifier.padding(top = 18.dp))
        }
    }
}

/** This visit's results, newest first - so the attendant can see who just went through. */
@Composable
private fun SessionLog(session: List<SessionResult>, modifier: Modifier) {
    if (session.isEmpty()) return
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text("Just now", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp))
            }
            session.forEach { result ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (result.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = if (result.success) "Recorded" else "Not recorded",
                        tint = if (result.success) BrandPrimary else AccentRed,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(result.name, fontWeight = FontWeight.Medium)
                        Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(displayTime(result.time), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Asked when leaving with face-confirmed attendance that hasn't reached the web admin yet. */
@Composable
private fun LeaveDialog(
    state: LeaveDialogState,
    onSaveAndSync: () -> Unit,
    onLeave: () -> Unit,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    if (state == LeaveDialogState.Hidden) return
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = state is LeaveDialogState.Prompt),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(24.dp)) {
                when (state) {
                    is LeaveDialogState.Prompt -> {
                        Text("Unsaved Attendance Records", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "You have ${state.unsyncedCount} attendance record(s) that have not been synchronized with the web admin yet. " +
                                "They are saved on this device. Do you want to save and sync them before leaving?",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Button(onClick = onSaveAndSync, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp)) {
                            Text("Save & Sync")
                        }
                        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
                            Text("Leave Without Syncing")
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("Cancel")
                        }
                    }
                    LeaveDialogState.Syncing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = BrandPrimary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Text("Synchronizing with the web admin...", modifier = Modifier.padding(start = 16.dp))
                    }
                    is LeaveDialogState.NotFinished -> {
                        Text("Saved on this device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = AccentGold, modifier = Modifier.padding(top = 12.dp))
                        Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("OK") }
                    }
                    LeaveDialogState.Hidden -> Unit
                }
            }
        }
    }
}
