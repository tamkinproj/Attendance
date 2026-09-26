package com.muslimedu.attendance.ui.screens.sync

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceHealthReport
import com.muslimedu.attendance.data.remote.dto.GateDeviceHeartbeatRequest
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.components.StatChip
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandPrimaryContainer
import com.muslimedu.attendance.util.DeviceHealth
import com.muslimedu.attendance.util.GateBackupCodec
import com.muslimedu.attendance.viewmodel.SyncViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Account, uploads and the student list. Background sync also runs on its own every 15 minutes while online. */
@Composable
fun SyncScreen(
    user: UserDto,
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
    val deviceHealth by viewModel.deviceHealth.collectAsState()
    val deviceNow by viewModel.deviceNow.collectAsState()
    val isReporting by viewModel.isReporting.collectAsState()
    val backupBusy by viewModel.backupBusy.collectAsState()
    val backupMessage by viewModel.backupMessage.collectAsState()
    val backupToShare by viewModel.backupToShare.collectAsState()
    val context = LocalContext.current
    var exportDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    // A backup file was just made: hand it to the share sheet (Drive, email, Files...).
    LaunchedEffect(backupToShare) {
        val export = backupToShare ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, export.uri)
            putExtra(Intent.EXTRA_SUBJECT, export.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Save the backup file")) }
        viewModel.backupShared()
    }
    if (exportDialog) {
        BackupPasswordDialog(
            title = "Export backup",
            confirmTwice = true,
            onConfirm = { password ->
                exportDialog = false
                viewModel.exportBackup(password)
            },
            onDismiss = { exportDialog = false },
        )
    }
    restoreUri?.let { uri ->
        BackupPasswordDialog(
            title = "Restore backup",
            confirmTwice = false,
            onConfirm = { password ->
                restoreUri = null
                viewModel.restoreBackup(uri, password)
            },
            onDismiss = { restoreUri = null },
        )
    }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    if (confirmSignOut) {
        SignOutDialog(
            pending = pending,
            onConfirm = {
                confirmSignOut = false
                onLogout()
            },
            onDismiss = { confirmSignOut = false },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
    ) {
        item { AccountCard(user, schoolId, onSignOut = { confirmSignOut = true }) }

        item {
            SectionHeader("Gate scans", modifier = Modifier.padding(top = 24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatChip(pending, "Waiting", AccentGold, AccentGoldContainer, Modifier.weight(1f))
                StatChip(synced, "Uploaded", BrandPrimary, BrandPrimaryContainer, Modifier.weight(1f))
                StatChip(failed.size, "Rejected", AccentRed, AccentRedContainer, Modifier.weight(1f))
            }
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::uploadNow, enabled = !isUploading) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Upload now", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (failed.isNotEmpty()) {
                    OutlinedButton(onClick = viewModel::retryFailed, enabled = !isUploading) {
                        Text("Retry rejected")
                    }
                }
            }
            uploadMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
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
            SectionHeader("This gate device", modifier = Modifier.padding(top = 24.dp))
            DeviceHealthCard(deviceHealth, deviceNow, isReporting, onReport = viewModel::reportDeviceHealth)
        }

        item {
            SectionHeader("Backup", modifier = Modifier.padding(top = 24.dp))
            BackupCard(
                busy = backupBusy,
                message = backupMessage,
                onExport = { exportDialog = true },
                onRestore = { pickBackup.launch(arrayOf("*/*")) },
            )
        }

        item {
            SectionHeader("Students", modifier = Modifier.padding(top = 24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("$studentCount student(s) on this device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        viewModel.lastStudentDownloadAt?.let { "Last downloaded ${formatDateTime(it)}" } ?: "Never downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = viewModel::downloadStudents,
                        enabled = !isDownloading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Download student list", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    downloadMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                    Text(
                        "Cards and faces already set up on this device are kept. " +
                            "Missing someone? Add them in Admin > Students with their real school code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(user: UserDto, schoolId: Int, onSignOut: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(user.name, BrandPrimary, size = 48.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Admin · this device belongs to school #$schoolId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSignOut) {
                Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Sign out", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/**
 * What the web's Gate Devices page sees of this phone: the name the admin
 * gave it there, when it last reported, and the live battery / reader /
 * clock state, with "Report now".
 */
@Composable
private fun DeviceHealthCard(
    report: DeviceHealthReport?,
    now: GateDeviceHeartbeatRequest?,
    isReporting: Boolean,
    onReport: () -> Unit,
) {
    val clock = DeviceHealth.clockWarning(report?.clockSkewSeconds)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                report?.webName ?: now?.model ?: "This phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Name it on the web: admin dashboard > Gate Devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val (statusText, statusColor) = when {
                report == null -> "Not reported to the school server yet" to MaterialTheme.colorScheme.onSurfaceVariant
                report.ok -> "Reported ${DeviceHealth.ago(report.at, System.currentTimeMillis())}" to BrandPrimary
                else -> "Last report didn't go through: ${report.message ?: "unknown error"}" +
                    (report.lastOkAt?.let { " (last OK ${DeviceHealth.ago(it, System.currentTimeMillis())})" } ?: "") to AccentGold
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor, modifier = Modifier.padding(top = 10.dp))
            now?.let { device ->
                val battery = device.batteryLevel?.let { level ->
                    "Battery $level%" + when (device.charging) {
                        true -> " · charging"
                        false -> " · not charging"
                        null -> ""
                    }
                } ?: "Battery unknown"
                Text(
                    listOf(
                        battery,
                        if (device.readerConnected) "Card reader connected" else "No card reader",
                        "App ${device.appVersion}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (clock != null) {
                Text(
                    "$clock - turn on automatic date & time in the phone's Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            OutlinedButton(onClick = onReport, enabled = !isReporting, modifier = Modifier.padding(top = 12.dp)) {
                if (isReporting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Report now")
                }
            }
        }
    }
}

/**
 * Every student's card, parent number and face in one password-protected
 * file - to set up a replacement or second gate phone without registering
 * everyone again, even with no internet.
 */
@Composable
private fun BackupCard(busy: Boolean, message: String?, onExport: () -> Unit, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Save every student's card, face and parent number to one file, protected by a password. If this phone " +
                    "is lost or broken, restore the file on the new phone (same school) - no registering everyone again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Export", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedButton(onClick = onRestore, enabled = !busy) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Restore", modifier = Modifier.padding(start = 8.dp))
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** The backup's password: twice when making one (it can't be recovered), once to restore. */
@Composable
private fun BackupPasswordDialog(title: String, confirmTwice: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val problem = when {
        password.length < GateBackupCodec.MIN_PASSWORD_LENGTH -> "At least ${GateBackupCodec.MIN_PASSWORD_LENGTH} characters"
        confirmTwice && again != password -> "The two passwords don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (confirmTwice) {
                        "Choose a password for the file. You need it to restore - it can't be recovered, so write it down somewhere safe."
                    } else {
                        "The password chosen when the backup was made."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = { Text("Password again") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                if (password.isNotEmpty() && problem != null) {
                    Text(problem, color = AccentRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = problem == null) { Text(if (confirmTwice) "Export" else "Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SignOutDialog(pending: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out of this gate?") },
        text = {
            Text(
                buildString {
                    append("The gate stops working until an admin signs in again.")
                    if (pending > 0) {
                        append(" $pending scan(s) haven't uploaded yet - they stay safely on this device and upload after the next sign-in.")
                    }
                },
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                Text("Sign out")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FailedScanRow(scan: GateScanEntity) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(scan.studentName ?: "Code ${scan.studentCode}", fontWeight = FontWeight.Medium)
            Text(
                "${scan.studentCode} · ${scan.direction} · ${scan.scanDate} ${scan.scanTime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scan.errorMessage?.let { Text(it, color = AccentRed, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
