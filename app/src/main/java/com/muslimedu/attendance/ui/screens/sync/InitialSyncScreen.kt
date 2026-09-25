package com.muslimedu.attendance.ui.screens.sync

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.components.BrandBackdrop
import com.muslimedu.attendance.ui.components.BrandLogo
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.InitialSyncViewModel
import com.muslimedu.attendance.viewmodel.SyncStepState
import com.muslimedu.attendance.viewmodel.SyncStepStatus

/**
 * Sign in -> **sync** -> gate. Runs automatically; the admin only acts if a
 * step fails (retry, or continue and sync later from Admin > Sync).
 */
@Composable
fun InitialSyncScreen(
    user: UserDto,
    viewModel: InitialSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.start() }

    BrandBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandLogo(size = 64.dp)
            Text(
                if (state.allDone) "This gate is ready" else "Getting this gate ready",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Signed in as ${user.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    StepRow(1, "Admin verified", SyncStepState(SyncStepStatus.Done, user.email))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StepRow(2, "Upload scans saved on this device", state.upload)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StepRow(3, "Download the student list", state.download)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    state.isRunning -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Syncing...") }
                    state.allDone -> Button(
                        onClick = viewModel::finish,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Open the gate", style = MaterialTheme.typography.titleMedium) }
                    else -> {
                        Button(
                            onClick = viewModel::start,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Try again", style = MaterialTheme.typography.titleMedium) }
                        TextButton(onClick = viewModel::finish, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Continue without syncing")
                        }
                        Text(
                            "Scans are still saved on this device. Sync any time from Admin > Sync & Account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, title: String, step: SyncStepState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepIndicatorDot(number, step.status)
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val detail = step.detail ?: when (step.status) {
                SyncStepStatus.Waiting -> "Waiting"
                SyncStepStatus.Running -> "In progress..."
                else -> null
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (step.status) {
                        SyncStepStatus.Failed -> AccentRed
                        SyncStepStatus.Skipped -> AccentGold
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun StepIndicatorDot(number: Int, status: SyncStepStatus) {
    val (fill, content) = when (status) {
        SyncStepStatus.Done -> BrandPrimary to Color.White
        SyncStepStatus.Failed -> AccentRed to Color.White
        SyncStepStatus.Skipped -> AccentGold to Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier.size(32.dp).background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SyncStepStatus.Done -> Icon(Icons.Filled.Check, contentDescription = "Done", tint = content, modifier = Modifier.size(18.dp))
            SyncStepStatus.Failed -> Icon(Icons.Filled.PriorityHigh, contentDescription = "Failed", tint = content, modifier = Modifier.size(18.dp))
            SyncStepStatus.Skipped -> Icon(Icons.Filled.Info, contentDescription = "Not available yet", tint = content, modifier = Modifier.size(18.dp))
            SyncStepStatus.Running -> CircularProgressIndicator(color = BrandPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            SyncStepStatus.Waiting -> Text("$number", color = content, style = MaterialTheme.typography.labelLarge)
        }
    }
}
