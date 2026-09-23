package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.remote.dto.GateAttendanceRecordDto
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanStudentDto
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.AccentTealContainer
import com.muslimedu.attendance.viewmodel.GateAttendanceViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.GateScanUiState

/**
 * Admin-only, campus-wide gate in/out attendance - any active student in the
 * school, independent of class/section. See [GateAttendanceViewModel]'s doc
 * comment and this app's CLAUDE.md "Gate In/Out Attendance" entry for why
 * this needed a new backend endpoint, not just a new screen.
 */
@Composable
fun GateAttendanceScreen(viewModel: GateAttendanceViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val direction by viewModel.direction.collectAsState()
    val todayRecords by viewModel.todayRecords.collectAsState()
    val isLoadingToday by viewModel.isLoadingToday.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // No heading here - AppRoot's TopAppBar already shows this
            // screen's title ("Gate In/Out Attendance"); repeating it as a
            // second, larger line right below just duplicated it.
            Text(
                text = "Scan any student campus-wide - not scoped to a class",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            DirectionToggle(direction = direction, onSelect = viewModel::setDirection)

            when (val state = uiState) {
                is GateScanUiState.Idle -> ScanEntryCard(
                    canSimulate = viewModel.canSimulate,
                    onSimulateScan = viewModel::simulateScan,
                    onScanByCode = viewModel::scanByCode,
                )
                is GateScanUiState.Scanning -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Recording...", modifier = Modifier.padding(top = 8.dp))
                }
                is GateScanUiState.Success -> GateResultCard(
                    student = state.student,
                    direction = state.direction,
                    isError = false,
                    onDismiss = viewModel::dismissResult,
                )
                is GateScanUiState.Failed -> GateResultCard(
                    student = null,
                    direction = direction,
                    isError = true,
                    message = state.message,
                    onDismiss = viewModel::dismissResult,
                )
            }

            Divider(modifier = Modifier.padding(vertical = 24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Today's Gate Activity", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = viewModel::refreshToday) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }

            if (isLoadingToday && todayRecords.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            } else if (todayRecords.isEmpty()) {
                Text(
                    text = "No gate scans recorded today yet.",
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(todayRecords) { record -> GateRecordRow(record) }
                }
            }
        }
    }
}

@Composable
private fun DirectionToggle(direction: GateDirection, onSelect: (GateDirection) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        GateDirection.entries.forEach { option ->
            val selected = option == direction
            val colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            val icon = if (option == GateDirection.IN) Icons.Filled.Login else Icons.Filled.Logout

            if (selected) {
                Button(
                    onClick = { onSelect(option) },
                    colors = colors,
                    modifier = Modifier.weight(1f).padding(end = if (option == GateDirection.IN) 4.dp else 0.dp, start = if (option == GateDirection.OUT) 4.dp else 0.dp),
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(option.label, modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f).padding(end = if (option == GateDirection.IN) 4.dp else 0.dp, start = if (option == GateDirection.OUT) 4.dp else 0.dp),
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(option.label, modifier = Modifier.padding(start = 8.dp))
                }
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
                Button(onClick = onSimulateScan, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Simulate Scan (debug)")
                }
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Student code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Button(
                onClick = { onScanByCode(code); code = "" },
                enabled = code.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Record")
            }
        }
    }
}

@Composable
private fun GateResultCard(
    student: GateAttendanceScanStudentDto?,
    direction: GateDirection,
    isError: Boolean,
    message: String? = null,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) AccentRedContainer else AccentTealContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isError) {
                Text("Gate Scan Failed", style = MaterialTheme.typography.titleLarge)
                Text(text = message ?: "Unknown error", color = AccentRed, modifier = Modifier.padding(top = 8.dp))
            } else if (student != null) {
                Text(student.studentName ?: "Unknown student", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (direction == GateDirection.IN) "Checked in at the gate" else "Checked out at the gate",
                    color = AccentTeal,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(text = "Time: ${student.lastTime ?: "-"}", modifier = Modifier.padding(top = 4.dp))
                if (student.checkInTime != null) {
                    Text(text = "First entry today: ${student.checkInTime}")
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
                Text("Scan Next")
            }
        }
    }
}

@Composable
private fun GateRecordRow(record: GateAttendanceRecordDto) {
    val isIn = record.lastDirection == "in"
    val color = if (isIn) AccentTeal else AccentGold

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(record.studentName ?: "Unknown", fontWeight = FontWeight.Medium)
                record.code?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Surface(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isIn) Icons.Filled.Login else Icons.Filled.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = color,
                    )
                    Text(
                        text = "${if (isIn) "In" else "Out"} - ${record.lastTime ?: "-"}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
