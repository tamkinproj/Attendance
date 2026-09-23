package com.muslimedu.attendance.ui.screens.sync

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.components.StatChip
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.AccentTealContainer
import com.muslimedu.attendance.viewmodel.SyncViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The only place login lives now. Signing in is needed only to upload gate
 * scans and download the student list - everything else works offline.
 */
@Composable
fun SyncScreen(
    user: UserDto?,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val pending by viewModel.pendingCount.collectAsState()
    val synced by viewModel.syncedCount.collectAsState()
    val failed by viewModel.failedScans.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val uploadMessage by viewModel.uploadMessage.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadMessage by viewModel.downloadMessage.collectAsState()
    val studentCount by viewModel.studentCount.collectAsState()
    val schoolId by viewModel.schoolId.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            item { AccountCard(user, schoolId, onSignIn, onLogout) }

            item {
                SectionHeader("Gate scans", modifier = Modifier.padding(top = 24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatChip(pending, "Waiting", AccentGold, AccentGoldContainer, Modifier.weight(1f))
                    StatChip(synced, "Uploaded", AccentTeal, AccentTealContainer, Modifier.weight(1f))
                    StatChip(failed.size, "Rejected", AccentRed, AccentRedContainer, Modifier.weight(1f))
                }
                Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::uploadNow, enabled = user != null && !isUploading) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Upload now")
                        }
                    }
                    if (failed.isNotEmpty()) {
                        OutlinedButton(onClick = viewModel::retryFailed, enabled = user != null && !isUploading) {
                            Text("Retry rejected")
                        }
                    }
                }
                uploadMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                if (user == null && pending > 0) {
                    Text(
                        "Scans are safe on this device. They upload automatically once an admin signs in and the device is online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (failed.isNotEmpty()) {
                item {
                    Text(
                        "Rejected by the server",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                items(failed, key = { it.id }) { scan -> FailedScanRow(scan) }
            }

            item {
                SectionHeader("Students", modifier = Modifier.padding(top = 24.dp))
                Text("$studentCount student(s) on this device", modifier = Modifier.padding(top = 8.dp))
                viewModel.lastStudentDownloadAt?.let {
                    Text(
                        "Last download: ${formatDateTime(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = viewModel::downloadStudents,
                    enabled = user != null && !isDownloading,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download students from server")
                    }
                }
                downloadMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                Text(
                    "Downloaded students keep any card or face already assigned on this device. " +
                        "You can also add a student by hand in Admin > Students using their real school code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountCard(user: UserDto?, schoolId: Int, onSignIn: () -> Unit, onLogout: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (user != null) {
                Text(user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(user.email, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onLogout, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Sign out")
                }
            } else {
                Text("Not signed in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Gate scanning works without signing in. Sign in with a school admin account to upload scans and download students.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSignIn, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Sign in")
                }
            }
            Text(
                if (schoolId == DeviceSettings.UNBOUND_SCHOOL_ID) {
                    "This device isn't linked to a school yet - the first admin sign-in links it."
                } else {
                    "This device is linked to school #$schoolId."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun FailedScanRow(scan: GateScanEntity) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(scan.studentName ?: "Code ${scan.studentCode}", fontWeight = FontWeight.Medium)
            Text(
                "${scan.studentCode} · ${scan.direction} · ${scan.scanDate} ${scan.scanTime}",
                style = MaterialTheme.typography.bodySmall,
            )
            scan.errorMessage?.let { Text(it, color = AccentRed, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
