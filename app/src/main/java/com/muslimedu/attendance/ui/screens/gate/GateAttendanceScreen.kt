package com.muslimedu.attendance.ui.screens.gate

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.components.BrandLogo
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.components.LiveFaceCaptureView
import com.muslimedu.attendance.ui.components.StatusPill
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandPrimaryContainer
import com.muslimedu.attendance.ui.theme.BrandTeal
import com.muslimedu.attendance.viewmodel.GateActivityRow
import com.muslimedu.attendance.viewmodel.GateAttendanceViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.GateScanUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The app's home: campus gate in/out. Designed to be read at a glance from a
 * step away - the In/Out direction recolors the whole scan area (teal for
 * In, amber for Out) so a wrong-direction scan is hard to miss. See
 * [GateAttendanceViewModel] for the scan rules and
 * [com.muslimedu.attendance.sync.GateSyncManager] for uploads.
 */
@Composable
fun GateAttendanceScreen(viewModel: GateAttendanceViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val direction by viewModel.direction.collectAsState()
    val activity by viewModel.todayActivity.collectAsState()
    val pendingUploads by viewModel.pendingUploadCount.collectAsState()
    val readerStatus by viewModel.readerStatus.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SummaryHeader(
                onCampus = activity.count { it.lastDirection == GateScanEntity.DIRECTION_IN },
                left = activity.count { it.lastDirection == GateScanEntity.DIRECTION_OUT },
                pendingUploads = pendingUploads,
            )
        }

        item { DirectionSwitch(direction = direction, onSelect = viewModel::setDirection) }

        item {
            when (val state = uiState) {
                is GateScanUiState.Idle -> ScanCard(
                    direction = direction,
                    readerStatus = readerStatus,
                    canSimulate = viewModel.canSimulate,
                    onSimulateScan = viewModel::simulateScan,
                    onScanByCode = viewModel::scanByCode,
                )
                is GateScanUiState.AwaitingFace -> FaceCheckCard(
                    state = state,
                    onCaptured = viewModel::onFaceCaptured,
                    onCancel = viewModel::cancelFaceCheck,
                )
                is GateScanUiState.VerifyingFace -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = BrandPrimary)
                        Text("Checking ${state.student.name}'s face...", modifier = Modifier.padding(top = 12.dp))
                    }
                }
                is GateScanUiState.Recorded -> RecordedCard(state, onDismiss = viewModel::dismissResult)
                is GateScanUiState.Failed -> FailedCard(state.message, onDismiss = viewModel::dismissResult)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Today at this gate",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${activity.size} student(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (activity.isEmpty()) {
                Text(
                    "No one has been scanned here today yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        items(activity, key = { it.code }) { row -> ActivityRow(row) }
    }
}

@Composable
private fun SummaryHeader(onCampus: Int, left: Int, pendingUploads: Int) {
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(BrandPrimary, BrandTeal))),
    ) {
        // The logo mark, oversized and faded white, as a watermark behind the stats.
        BrandLogo(
            size = 140.dp,
            tint = Color.White,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 36.dp).alpha(0.14f),
        )
        Column(modifier = Modifier.padding(20.dp)) {
            Text(today, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeaderStat(onCampus, "On campus", Modifier.weight(1f))
                HeaderStat(left, "Left", Modifier.weight(1f))
                HeaderStat(pendingUploads, "To upload", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeaderStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.16f), shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

private fun GateDirection.accent(): Color = if (this == GateDirection.IN) BrandPrimary else AccentGold

/** Two big segments in one track - the selected one fills with its direction's color. */
@Composable
private fun DirectionSwitch(direction: GateDirection, onSelect: (GateDirection) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(percent = 50),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            GateDirection.entries.forEach { option ->
                val selected = option == direction
                val fill by animateColorAsState(if (selected) option.accent() else Color.Transparent, label = "direction")
                Surface(
                    onClick = { onSelect(option) },
                    color = fill,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        val tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(
                            if (option == GateDirection.IN) Icons.Filled.Login else Icons.Filled.Logout,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            if (option == GateDirection.IN) "Coming in" else "Going out",
                            color = tint,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanCard(
    direction: GateDirection,
    readerStatus: ReaderStatus,
    canSimulate: Boolean,
    onSimulateScan: () -> Unit,
    onScanByCode: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val accent = direction.accent()
    val submit = {
        if (code.isNotBlank()) {
            onScanByCode(code)
            code = ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(104.dp).background(accent.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(72.dp).background(accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Nfc, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
                }
            }
            Text(
                if (direction == GateDirection.IN) "Tap a card to check in" else "Tap a card to check out",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            ReaderLine(readerStatus, modifier = Modifier.padding(top = 6.dp))

            if (canSimulate) {
                OutlinedButton(onClick = onSimulateScan, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Simulate scan (debug)")
                }
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Or type a student code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { submit() }, enabled = code.isNotBlank()) {
                        Icon(Icons.Filled.Send, contentDescription = "Record", tint = if (code.isNotBlank()) accent else MaterialTheme.colorScheme.outline)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun ReaderLine(status: ReaderStatus, modifier: Modifier = Modifier) {
    val (color, label) = when {
        status.connected -> BrandPrimary to (status.deviceName?.let { "Reader ready - $it" } ?: "Reader ready")
        status.canSimulate -> AccentGold to "No reader - use simulate or type a code"
        else -> AccentRed to "No card reader detected - plug in the USB reader"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Same auto-capture camera the classroom flow used - no manual override, so a failed check records nothing. */
@Composable
private fun FaceCheckCard(
    state: GateScanUiState.AwaitingFace,
    onCaptured: (android.graphics.Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.failureReason != null) AccentRedContainer else BrandPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Look at the camera", style = MaterialTheme.typography.titleLarge)
            Text("${state.student.name} - ${if (state.direction == GateDirection.IN) "coming in" else "going out"}")
            state.failureReason?.let { Text(it, color = AccentRed, modifier = Modifier.padding(top = 8.dp)) }
            LiveFaceCaptureView(
                onCaptured = onCaptured,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clip(MaterialTheme.shapes.medium),
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 12.dp)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun RecordedCard(state: GateScanUiState.Recorded, onDismiss: () -> Unit) {
    val scan = state.scan
    val isIn = scan.direction == GateScanEntity.DIRECTION_IN
    val accent = if (isIn) BrandPrimary else AccentGold

    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            InitialsAvatar(scan.studentName ?: scan.studentCode, accent, size = 72.dp)
            Text(
                scan.studentName ?: "Code ${scan.studentCode}",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            StatusPill(
                label = "${if (isIn) "Checked in" else "Checked out"} at ${scan.scanTime}",
                color = accent,
                icon = if (isIn) Icons.Filled.Login else Icons.Filled.Logout,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (scan.verifiedByFace) {
                StatusPill("Face verified", BrandPrimary, Icons.Filled.VerifiedUser, Modifier.padding(top = 6.dp))
            }
            if (state.isDuplicate) {
                Text(
                    "Already recorded moments ago - not counted twice",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!state.onDevice) {
                Text(
                    "This code isn't on this device - the server will check it when scans upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Next student")
            }
        }
    }
}

@Composable
private fun FailedCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentRedContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AccentRed, modifier = Modifier.size(40.dp))
            Text("Not recorded", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(message, color = AccentRed, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("OK")
            }
        }
    }
}

@Composable
private fun ActivityRow(row: GateActivityRow) {
    val isIn = row.lastDirection == GateScanEntity.DIRECTION_IN
    val color = if (isIn) BrandPrimary else AccentGold
    val (syncIcon, syncTint, syncLabel) = when (row.syncStatus) {
        GateScanEntity.SYNC_SYNCED -> Triple(Icons.Filled.CloudDone, BrandPrimary, "Uploaded")
        GateScanEntity.SYNC_FAILED -> Triple(Icons.Filled.ErrorOutline, AccentRed, "Rejected by server")
        else -> Triple(Icons.Filled.CloudUpload, MaterialTheme.colorScheme.outline, "Waiting to upload")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(row.name ?: row.code, color)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(row.name ?: "Code ${row.code}", fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(row.code, row.firstInTime?.let { "first in $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                label = "${if (isIn) "In" else "Out"} ${row.lastTime}",
                color = color,
                icon = if (isIn) Icons.Filled.Login else Icons.Filled.Logout,
            )
            Icon(
                syncIcon,
                contentDescription = syncLabel,
                tint = syncTint,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
    }
}
