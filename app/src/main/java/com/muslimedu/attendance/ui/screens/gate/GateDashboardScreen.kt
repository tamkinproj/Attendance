package com.muslimedu.attendance.ui.screens.gate

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.components.BrandLogo
import com.muslimedu.attendance.ui.theme.AccentBlue
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date

private val CardShape = RoundedCornerShape(24.dp)

/**
 * The gate's home. Scanning doesn't start here: the attendant first picks
 * Coming In or Going Out, which opens that direction's own RFID screen
 * ([GateScanScreen]). Below: whether records have reached the web admin, and
 * the most recent records with their RFID/face/sync status.
 *
 * Draws its own header (large title + the admin button) instead of the app
 * bar. Colors are the brand accents laid over the surface color, so the soft
 * tinted cards work in dark mode too.
 */
@Composable
fun GateDashboardScreen(
    adminName: String,
    onAdmin: () -> Unit,
    onOpen: (GateDirection) -> Unit,
    onHistory: () -> Unit,
    onSetUpSchedule: () -> Unit,
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
    val schedule by viewModel.schedule.collectAsState()
    val now by viewModel.now.collectAsState()
    val clockWarning by viewModel.clockWarning.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header(adminName, onAdmin) }

        item { SummaryCard(today, unsynced) }

        clockWarning?.let { warning -> item { ClockWarningCard(warning) } }

        // The gate can't be used until an admin sets how many scans a day each student makes.
        if (schedule == null) item { ScheduleSetupCard(onSetUpSchedule) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GateDirection.entries.forEach { direction ->
                    // Locked until the schedule's first opening for that direction;
                    // it unlocks by itself at that time (the view model's clock ticks).
                    val opensAt = schedule?.firstOpening(direction.apiValue)?.takeIf { now < it }
                    if (opensAt != null) {
                        LockedDirectionCard(direction, opensAt, Modifier.weight(1f))
                    } else {
                        DirectionCard(direction, Modifier.weight(1f)) {
                            if (schedule == null) onSetUpSchedule() else onOpen(direction)
                        }
                    }
                }
            }
            schedule?.let {
                Text(
                    "Each student: ${it.perDay} Coming In · ${it.perDay} Going Out per day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            // Only speaks up when there's a problem - a working reader needs no label.
            if (!readerStatus.connected) ReaderWarning(readerStatus, Modifier.padding(top = 10.dp, start = 4.dp))
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
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("RFID scan history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Most recent records at this gate",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(onClick = onHistory, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("View all", style = MaterialTheme.typography.titleMedium, color = BrandPrimary, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = BrandPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (recent.isEmpty()) EmptyHistory()
        }

        items(recent, key = { it.id }) { scan -> GateRecordRow(scan, isSyncing, showDate = true) }
    }
}

@Composable
private fun Header(adminName: String, onAdmin: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(tint(BrandTeal, 0.14f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BrandLogo(size = 36.dp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Gate Attendance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(adminName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            onClick = onAdmin,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = "Admin",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** The brand accent laid over the current surface - a soft tint in light mode, a deep one in dark. */
@Composable
private fun tint(color: Color, alpha: Float): Color = color.copy(alpha = alpha).compositeOver(MaterialTheme.colorScheme.surface)

@Composable
private fun SummaryCard(today: GateTodayStats, unsynced: Int) {
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) }
    val top = tint(BrandTeal, 0.08f)
    val bottom = tint(BrandTeal, 0.18f)
    val ring = BrandTeal.copy(alpha = 0.10f)
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, tint(BrandTeal, 0.16f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithCache {
                    val fill = Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset(size.width, size.height))
                    onDrawBehind {
                        drawRect(fill)
                        // Two soft arcs in the top-right corner, echoing the mockup's glass rings.
                        val center = Offset(size.width * 1.02f, size.height * 0.95f)
                        drawCircle(ring, radius = size.height * 0.95f, center = center, style = Stroke(width = size.height * 0.10f))
                        drawCircle(ring, radius = size.height * 0.62f, center = center)
                    }
                },
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
                Text(date, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stat(today.onCampus, "On campus", BrandPrimary, Modifier.weight(1f))
                    StatDivider()
                    Stat(today.left, "Left", AccentBlue, Modifier.weight(1f))
                    StatDivider()
                    Stat(unsynced, "Pending sync", AccentGold, Modifier.weight(1f))
                }
                if (today.late > 0) {
                    Row(modifier = Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AlarmOn, contentDescription = null, tint = AccentGold, modifier = Modifier.size(16.dp))
                        Text(
                            "${today.late} late arrival(s) today",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentGold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (today.failed > 0) {
                    Text(
                        "${today.failed} failed face check(s) today - not recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, dot: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Soft tinted card per direction - the only way into scanning. */
@Composable
private fun DirectionCard(direction: GateDirection, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = direction.color()
    val top = tint(color, 0.05f)
    val bottom = tint(color, 0.13f)
    Card(
        onClick = onClick,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, tint(color, 0.14f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithCache {
                    val fill = Brush.verticalGradient(listOf(top, bottom))
                    onDrawBehind { drawRect(fill) }
                }
                .padding(18.dp),
        ) {
            Box(
                modifier = Modifier.size(60.dp).background(color.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(directionIcon(direction.apiValue), contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
            }
            Row(modifier = Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    direction.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "RFID + face check",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
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

/** A direction before its first opening time today: not tappable, says when it opens. */
@Composable
private fun LockedDirectionCard(direction: GateDirection, opensAt: LocalTime, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Box(
                modifier = Modifier.size(60.dp).background(color.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
            }
            Text(
                direction.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                "Opens at ${displayTime(opensAt.toString())}",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The server found this phone's clock off. Every scan is stamped with it,
 * so time in/out, Late and the parent texts would all be wrong - the card
 * opens the phone's date & time settings.
 */
@Composable
private fun ClockWarningCard(warning: String) {
    val context = LocalContext.current
    Card(
        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) } },
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = tint(AccentRed, 0.10f)),
        border = BorderStroke(1.dp, tint(AccentRed, 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(AccentRed.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = AccentRed, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(warning, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Scan times, Late and parent texts will be wrong. Tap to turn on automatic date & time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AccentRed)
        }
    }
}

/** Shown until the admin sets the gate schedule; Coming In / Going Out lead here too. */
@Composable
private fun ScheduleSetupCard(onSetUp: () -> Unit) {
    Card(
        onClick = onSetUp,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = tint(AccentGold, 0.12f)),
        border = BorderStroke(1.dp, tint(AccentGold, 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(AccentGold.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = AccentGold, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Set up the gate first", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "An admin chooses how many Coming In and Going Out scans each student makes per day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AccentGold)
        }
    }
}

@Composable
private fun ReaderWarning(status: ReaderStatus, modifier: Modifier = Modifier) = ReaderLine(status, modifier)

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
    val allSynced = unsynced == 0 && !isSyncing
    val color = when {
        isSyncing -> AccentSlate
        allSynced -> BrandPrimary
        else -> AccentGold
    }
    val title = when {
        isSyncing -> "Synchronizing..."
        allSynced -> "All attendance synced"
        !isOnline -> "Offline - $unsynced pending"
        else -> "$unsynced record(s) pending sync"
    }
    val subtitle = when {
        !isOnline && unsynced > 0 -> "Saved on this device. They sync automatically when the connection returns."
        lastSyncedAt != null && allSynced -> "Last synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSyncedAt))}"
        else -> "Records sync with the web admin automatically."
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).background(color.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                        when {
                            isSyncing -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            allSynced -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            !isOnline -> Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            else -> Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                    Text("Sync status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // "Synced" when there's nothing to send; otherwise the pill is the Sync now button.
                Surface(
                    onClick = onSyncNow,
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(percent = 50),
                    color = color.copy(alpha = 0.12f),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Cloud, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                        Text(
                            if (allSynced) "Synced" else "Sync now",
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            if (failedUploads > 0) {
                Text(
                    "$failedUploads record(s) refused by the server - see Admin > Sync & Account",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                    modifier = Modifier.padding(top = 12.dp),
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

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(84.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp),
            )
        }
        Text("No scans yet.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Choose Coming In or Going Out to start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
