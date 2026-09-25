package com.muslimedu.attendance.ui.screens.gate

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.components.BrandLogo
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSlate
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandTeal
import com.muslimedu.attendance.viewmodel.GateDashboardViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.GateTodayStats
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * The gate's home. Scanning doesn't start here: the attendant first picks
 * Coming In or Going Out, which opens that direction's own RFID screen
 * ([GateScanScreen]). Below: whether records have reached the web admin, and
 * the most recent records with their RFID/face/sync status.
 */
@Composable
fun GateDashboardScreen(
    onOpen: (GateDirection) -> Unit,
    onHistory: () -> Unit,
    viewModel: GateDashboardViewModel = hiltViewModel(),
) {
    val today by viewModel.today.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val unsynced by viewModel.unsyncedCount.collectAsState()
    val failedUploads by viewModel.failedUploadCount.collectAsState()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val readerStatus by viewModel.readerStatus.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryHeader(today, unsynced) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DirectionButton(GateDirection.IN, Modifier.weight(1f)) { onOpen(GateDirection.IN) }
                DirectionButton(GateDirection.OUT, Modifier.weight(1f)) { onOpen(GateDirection.OUT) }
            }
            ReaderLine(readerStatus, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        }

        item {
            SyncStatusCard(
                unsynced = unsynced,
                failedUploads = failedUploads,
                lastSyncedAt = lastSyncedAt,
                isSyncing = isSyncing,
                isOnline = isOnline,
                message = syncMessage,
                onSyncNow = viewModel::syncNow,
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("RFID scan history", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Most recent records at this gate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onHistory) { Text("View all") }
            }
            if (recent.isEmpty()) {
                Text(
                    "No scans yet. Choose Coming In or Going Out to start.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        items(recent, key = { it.id }) { scan -> GateRecordRow(scan, isSyncing, showDate = true) }
    }
}

@Composable
private fun SummaryHeader(today: GateTodayStats, unsynced: Int) {
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(BrandPrimary, BrandTeal))),
    ) {
        BrandLogo(
            size = 140.dp,
            tint = Color.White,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 36.dp).alpha(0.14f),
        )
        Column(modifier = Modifier.padding(20.dp)) {
            Text(date, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeaderStat(today.onCampus, "On campus", Modifier.weight(1f))
                HeaderStat(today.left, "Left", Modifier.weight(1f))
                HeaderStat(unsynced, "Pending sync", Modifier.weight(1f))
            }
            if (today.failed > 0) {
                Text(
                    "${today.failed} failed face check(s) today - not recorded",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
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

/** A big, colored tile per direction - the only way into scanning. */
@Composable
private fun DirectionButton(direction: GateDirection, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = direction.color()
    Card(
        onClick = onClick,
        modifier = modifier.height(132.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(directionIcon(direction.apiValue), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(direction.label, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("RFID + face check", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ReaderLine(status: ReaderStatus, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val (color, label) = if (status.connected) {
        BrandPrimary to (status.deviceName?.let { "RFID reader ready - $it" } ?: "RFID reader ready")
    } else {
        AccentRed to "No RFID reader detected - plug in the USB reader"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(if (onDark && status.connected) Color.White else color, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (onDark) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SyncStatusCard(
    unsynced: Int,
    failedUploads: Int,
    lastSyncedAt: Long?,
    isSyncing: Boolean,
    isOnline: Boolean,
    message: String?,
    onSyncNow: () -> Unit,
) {
    val (icon, color, title) = when {
        isSyncing -> Triple(Icons.Filled.Sync, AccentSlate, "Synchronizing...")
        unsynced == 0 -> Triple(Icons.Filled.CloudDone, BrandPrimary, "All attendance synced")
        !isOnline -> Triple(Icons.Filled.CloudOff, AccentGold, "Offline - $unsynced record(s) pending sync")
        else -> Triple(Icons.Filled.CloudUpload, AccentGold, "$unsynced record(s) pending sync")
    }
    val subtitle = when {
        !isOnline && unsynced > 0 -> "Saved on this device. They sync automatically when the connection returns."
        lastSyncedAt != null -> "Last synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSyncedAt))}"
        else -> "Records sync with the web admin automatically."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Sync status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onSyncNow, enabled = !isSyncing, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Sync now")
                }
            }
            if (failedUploads > 0) {
                Text(
                    "$failedUploads record(s) refused by the server - see Admin > Sync & Account",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
