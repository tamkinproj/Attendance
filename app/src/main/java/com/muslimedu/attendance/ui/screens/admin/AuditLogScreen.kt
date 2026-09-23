package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.AuditLogEntity
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.ui.components.EmptyState
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.BrandPurple
import com.muslimedu.attendance.ui.theme.TextSecondary
import com.muslimedu.attendance.viewmodel.AuditLogViewModel
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Surfaces [AuditLogger]'s existing audit_logs table - that data was already
 * being written (login/logout, attendance recorded, manual override, face
 * enrolled, RFID assigned, student added) but had no viewer anywhere in the
 * app before this.
 */
@Composable
fun AuditLogScreen(viewModel: AuditLogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.History,
                title = "No audit entries yet",
                message = "Logins, attendance, and enrollment actions will show up here.",
                modifier = Modifier.padding(top = 64.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(entries, key = { it.id }) { entry -> AuditLogRow(entry) }
            }
        }
    }
}

@Composable
private fun AuditLogRow(entry: AuditLogEntity) {
    val (icon, color) = actionIcon(entry.action)
    val isFailure = entry.status == AuditLogEntity.STATUS_FAILURE

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = color)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(actionLabel(entry.action), fontWeight = FontWeight.Medium)
                    if (isFailure) {
                        Surface(
                            color = AccentRed.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(percent = 50),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                "FAILED",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentRed,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = entry.userId ?: "system",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.details?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AccentRed)
                }
            }
            Text(
                text = formatTimestamp(entry.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun actionIcon(action: String): Pair<ImageVector, Color> = when (action) {
    AuditLogger.ACTION_LOGIN -> Icons.Filled.Login to AccentTeal
    AuditLogger.ACTION_LOGOUT -> Icons.Filled.Logout to AccentGold
    AuditLogger.ACTION_ATTENDANCE_RECORDED -> Icons.Filled.QrCodeScanner to BrandPurple
    AuditLogger.ACTION_MANUAL_OVERRIDE -> Icons.Filled.Warning to AccentGold
    AuditLogger.ACTION_FACE_ENROLLED -> Icons.Filled.Face to AccentTeal
    AuditLogger.ACTION_RFID_ASSIGNED -> Icons.Filled.CreditCard to AccentTeal
    AuditLogger.ACTION_STUDENT_ADDED -> Icons.Filled.PersonAdd to BrandPurple
    else -> Icons.Filled.History to TextSecondary
}

private fun actionLabel(action: String): String = when (action) {
    AuditLogger.ACTION_LOGIN -> "Login"
    AuditLogger.ACTION_LOGOUT -> "Logout"
    AuditLogger.ACTION_ATTENDANCE_RECORDED -> "Attendance Recorded"
    AuditLogger.ACTION_MANUAL_OVERRIDE -> "Manual Override"
    AuditLogger.ACTION_FACE_ENROLLED -> "Face Enrolled"
    AuditLogger.ACTION_RFID_ASSIGNED -> "RFID Assigned"
    AuditLogger.ACTION_STUDENT_ADDED -> "Student Added"
    else -> action
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMillis))
