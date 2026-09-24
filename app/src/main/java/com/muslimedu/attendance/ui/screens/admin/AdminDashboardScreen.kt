package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.components.NetworkStatusBadge
import com.muslimedu.attendance.ui.components.QuickActionCard
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.components.StatChip
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentSuccess
import com.muslimedu.attendance.ui.theme.AccentSuccessContainer
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandPrimaryContainer
import com.muslimedu.attendance.viewmodel.AdminDashboardViewModel
import com.muslimedu.attendance.viewmodel.AdminStats

@Composable
fun AdminDashboardScreen(
    onManageStudents: () -> Unit,
    onEnrollFace: () -> Unit,
    onEnrollRfid: () -> Unit,
    onTakeAttendance: () -> Unit,
    onSettings: () -> Unit,
    onExportAttendance: () -> Unit,
    onSyncRoster: () -> Unit,
    isSyncingRoster: Boolean,
    onBrowseByClass: () -> Unit,
    onGateAttendance: () -> Unit,
    onSyncStatus: () -> Unit,
    onAuditLog: () -> Unit,
    viewModel: AdminDashboardViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsState()
    val isRetrying by viewModel.isRetrying.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    // AdminDashboardViewModel's own student count is a snapshot from when
    // this screen last loaded - it has no visibility into RosterViewModel's
    // sync finishing, so without this a just-synced student wouldn't show up
    // in "Total students" until the dashboard was reopened.
    LaunchedEffect(isSyncingRoster) {
        if (!isSyncingRoster) viewModel.refresh()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Admin Dashboard", style = MaterialTheme.typography.titleLarge)
            NetworkStatusBadge(isOnline = isOnline, modifier = Modifier.padding(top = 8.dp))

            when (val current = stats) {
                null -> CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                else -> StatsCard(current, onSyncStatus = onSyncStatus)
            }

            SectionHeader("Attendance", modifier = Modifier.padding(top = 24.dp))
            ActionGrid(
                listOf(
                    QuickAction(Icons.Filled.QrCodeScanner, "Take Attendance", BrandPrimary, onTakeAttendance),
                    QuickAction(Icons.Filled.Login, "Gate In/Out", AccentSuccess, onGateAttendance),
                    QuickAction(
                        Icons.Filled.Autorenew,
                        if (isRetrying) "Retrying..." else "Retry Failed Syncs",
                        AccentRed,
                        viewModel::retryFailedSyncs,
                        enabled = !isRetrying && (stats?.failedToday ?: 0) > 0,
                    ),
                ),
            )

            SectionHeader("Students", modifier = Modifier.padding(top = 24.dp))
            ActionGrid(
                listOf(
                    QuickAction(
                        Icons.Filled.CloudSync,
                        if (isSyncingRoster) "Syncing..." else "Sync Roster",
                        AccentGold,
                        onSyncRoster,
                        enabled = !isSyncingRoster,
                    ),
                    QuickAction(Icons.Filled.People, "Student List", BrandPrimary, onManageStudents),
                    QuickAction(Icons.Filled.Groups, "Browse by Class", AccentSuccess, onBrowseByClass),
                    QuickAction(Icons.Filled.CreditCard, "Enroll RFID", AccentGold, onEnrollRfid),
                    QuickAction(Icons.Filled.Face, "Enroll Face", AccentSuccess, onEnrollFace),
                ),
            )

            SectionHeader("More", modifier = Modifier.padding(top = 24.dp))
            ActionGrid(
                listOf(
                    QuickAction(Icons.Filled.Sync, "Sync Status", BrandPrimary, onSyncStatus),
                    QuickAction(Icons.Filled.FileDownload, "Export CSV", AccentGold, onExportAttendance),
                    QuickAction(Icons.Filled.Settings, "Settings", AccentSuccess, onSettings),
                    QuickAction(Icons.Filled.History, "Audit Log", AccentRed, onAuditLog),
                ),
            )
        }
    }
}

private data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/** Fixed 2-column grid (not a wrapping FlowRow) so this stays compatible without an experimental-API opt-in. */
@Composable
private fun ActionGrid(actions: List<QuickAction>) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        actions.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { action ->
                    QuickActionCard(
                        icon = action.icon,
                        label = action.label,
                        color = action.color,
                        onClick = action.onClick,
                        enabled = action.enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A grid of colored stat chips (student counts, today's sync status) -
 * matches the reference mockup's "Session Details" stat-card layout rather
 * than a plain label/value list. Tapping the sync row jumps to the dedicated
 * Sync Status screen instead of duplicating its failed-record detail here.
 */
@Composable
private fun StatsCard(stats: AdminStats, onSyncStatus: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Roster", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip(stats.totalStudents, "Students", BrandPrimary, BrandPrimaryContainer, Modifier.weight(1f))
            StatChip(stats.studentsWithFace, "Face", AccentSuccess, AccentSuccessContainer, Modifier.weight(1f))
            StatChip(stats.studentsWithRfid, "RFID", AccentGold, AccentGoldContainer, Modifier.weight(1f))
        }

        Text(
            "Today's attendance sync",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSyncStatus),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatChip(stats.syncedToday, "Synced", AccentSuccess, AccentSuccessContainer, Modifier.weight(1f))
            StatChip(stats.pendingToday, "Pending", AccentGold, AccentGoldContainer, Modifier.weight(1f))
            StatChip(stats.failedToday, "Failed", AccentRed, AccentRedContainer, Modifier.weight(1f))
        }
        Text(
            "Tap for sync details and failed-record errors",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
