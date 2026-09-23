package com.muslimedu.attendance.ui.screens.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.ui.components.LiveFaceCaptureView
import com.muslimedu.attendance.ui.components.ReaderStatusBadge
import com.muslimedu.attendance.ui.components.StatusPill
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.AccentTealContainer
import com.muslimedu.attendance.ui.theme.BrandPurple
import com.muslimedu.attendance.ui.theme.BrandPurpleContainer
import com.muslimedu.attendance.viewmodel.GateActivityRow
import com.muslimedu.attendance.viewmodel.GateAttendanceViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.GateScanUiState

/**
 * The app's home screen: campus gate in/out, with no login and no network
 * needed. See [GateAttendanceViewModel] for the scan rules and
 * [com.muslimedu.attendance.sync.GateSyncManager] for how scans upload.
 */
@Composable
fun GateAttendanceScreen(viewModel: GateAttendanceViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val direction by viewModel.direction.collectAsState()
    val activity by viewModel.todayActivity.collectAsState()
    val pendingUploads by viewModel.pendingUploadCount.collectAsState()
    val readerStatus by viewModel.readerStatus.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            item {
                ReaderStatusBadge(readerStatus)
                UploadStatusLine(pendingUploads, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                DirectionToggle(direction = direction, onSelect = viewModel::setDirection)
            }

            item {
                when (val state = uiState) {
                    is GateScanUiState.Idle -> ScanEntryCard(
                        canSimulate = viewModel.canSimulate,
                        onSimulateScan = viewModel::simulateScan,
                        onScanByCode = viewModel::scanByCode,
                    )
                    is GateScanUiState.AwaitingFace -> FaceCheckCard(
                        state = state,
                        onCaptured = viewModel::onFaceCaptured,
                        onCancel = viewModel::cancelFaceCheck,
                    )
                    is GateScanUiState.VerifyingFace -> Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("Checking ${state.student.name}'s face...", modifier = Modifier.padding(top = 8.dp))
                    }
                    is GateScanUiState.Recorded -> RecordedCard(state, onDismiss = viewModel::dismissResult)
                    is GateScanUiState.Failed -> FailedCard(state.message, onDismiss = viewModel::dismissResult)
                }
            }

            item {
                Text(
                    "Today on this gate",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                if (activity.isEmpty()) {
                    Text("No gate scans on this device today yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            items(activity, key = { it.code }) { row -> ActivityRow(row) }
        }
    }
}

@Composable
private fun UploadStatusLine(pendingUploads: Int, modifier: Modifier = Modifier) {
    val (icon, color, label) = if (pendingUploads == 0) {
        Triple(Icons.Filled.CloudDone, AccentTeal, "All scans uploaded")
    } else {
        Triple(Icons.Filled.CloudUpload, AccentGold, "$pendingUploads scan(s) saved on this device, waiting to upload")
    }
    StatusPill(label = label, color = color, icon = icon, modifier = modifier)
}

@Composable
private fun DirectionToggle(direction: GateDirection, onSelect: (GateDirection) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GateDirection.entries.forEach { option ->
            val icon = if (option == GateDirection.IN) Icons.Filled.Login else Icons.Filled.Logout
            val content: @Composable () -> Unit = {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(option.label, modifier = Modifier.padding(start = 8.dp))
            }
            if (option == direction) {
                Button(onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) { content() }
            } else {
                OutlinedButton(onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun ScanEntryCard(
    canSimulate: Boolean,
    onSimulateScan: () -> Unit,
    onScanByCode: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Tap a student's card, or enter their code", style = MaterialTheme.typography.titleMedium)

            if (canSimulate) {
                OutlinedButton(onClick = onSimulateScan, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Simulate Scan (debug)")
                }
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Student code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                onClick = {
                    onScanByCode(code)
                    code = ""
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Record")
            }
        }
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
            containerColor = if (state.failureReason != null) AccentRedContainer else BrandPurpleContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Face check", style = MaterialTheme.typography.titleLarge)
            Text("${state.student.name} - ${state.direction.label}")
            state.failureReason?.let { Text(it, color = AccentRed, modifier = Modifier.padding(top = 8.dp)) }
            LiveFaceCaptureView(onCaptured = onCaptured, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
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

    Card(
        colors = CardDefaults.cardColors(containerColor = AccentTealContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(scan.studentName ?: "Code ${scan.studentCode}", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${if (isIn) "Checked in" else "Checked out"} at ${scan.scanTime}",
                color = AccentTeal,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (scan.verifiedByFace) {
                StatusPill("Face verified", AccentTeal, Icons.Filled.VerifiedUser, Modifier.padding(top = 8.dp))
            }
            if (state.isDuplicate) {
                Text("Already recorded moments ago - not counted twice", modifier = Modifier.padding(top = 8.dp))
            }
            if (!state.onDevice) {
                Text(
                    "This code isn't on this device - the server will check it when scans upload.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("Scan Next")
            }
        }
    }
}

@Composable
private fun FailedCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AccentRedContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Not recorded", style = MaterialTheme.typography.titleLarge)
            Text(message, color = AccentRed, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("OK")
            }
        }
    }
}

@Composable
private fun ActivityRow(row: GateActivityRow) {
    val isIn = row.lastDirection == GateScanEntity.DIRECTION_IN
    val color = if (isIn) AccentTeal else AccentGold
    val (syncIcon, syncTint) = when (row.syncStatus) {
        GateScanEntity.SYNC_SYNCED -> Icons.Filled.CloudDone to AccentTeal
        GateScanEntity.SYNC_FAILED -> Icons.Filled.ErrorOutline to AccentRed
        else -> Icons.Filled.CloudUpload to BrandPurple
    }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name ?: "Code ${row.code}", fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(row.code, row.firstInTime?.let { "first in $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusPill(
                label = "${if (isIn) "In" else "Out"} ${row.lastTime}",
                color = color,
                icon = if (isIn) Icons.Filled.Login else Icons.Filled.Logout,
            )
            Icon(
                syncIcon,
                contentDescription = row.syncStatus,
                tint = syncTint,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
    }
}
