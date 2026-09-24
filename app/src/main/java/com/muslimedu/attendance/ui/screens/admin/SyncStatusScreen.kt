package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.components.EmptyState
import com.muslimedu.attendance.ui.components.NetworkStatusBadge
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.components.StatChip
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentSuccess
import com.muslimedu.attendance.ui.theme.AccentSuccessContainer
import com.muslimedu.attendance.viewmodel.FailedRecord
import com.muslimedu.attendance.viewmodel.SyncStatusViewModel

@Composable
fun SyncStatusScreen(viewModel: SyncStatusViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsState()
    val failedRecords by viewModel.failedRecords.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    // Same reasoning as the other screens' refresh-on-visit: this ViewModel
    // outlives navigating away, so counts would otherwise be a snapshot from
    // whenever it was first constructed.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            NetworkStatusBadge(isOnline = isOnline, modifier = Modifier.padding(bottom = 16.dp))

            when (val current = summary) {
                null -> CircularProgressIndicator()
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip(current.syncedToday, "Synced", AccentSuccess, AccentSuccessContainer, Modifier.weight(1f))
                        StatChip(current.pendingToday, "Pending", AccentGold, AccentGoldContainer, Modifier.weight(1f))
                        StatChip(current.failedToday, "Failed", AccentRed, AccentRedContainer, Modifier.weight(1f))
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::syncNow,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isBusy) "Syncing..." else "Sync Now")
                }
                OutlinedButton(
                    onClick = viewModel::retryFailedSyncs,
                    enabled = !isBusy && (summary?.failedToday ?: 0) > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Retry Failed")
                }
            }

            SectionHeader("Failed Records", modifier = Modifier.padding(top = 24.dp))
            if (failedRecords.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = "No failed syncs today",
                    message = "Everything recorded today has synced or is still pending.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn {
                    items(failedRecords) { record -> FailedRecordCard(record) }
                }
            }
        }
    }
}

@Composable
private fun FailedRecordCard(record: FailedRecord) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = AccentRed,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(record.studentName, fontWeight = FontWeight.Medium)
                Text("Checked in at ${record.checkInTime}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = record.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "Attempts: ${record.syncAttempts}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
