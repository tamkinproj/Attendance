package com.muslimedu.attendance.ui.screens.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.theme.AccentBlue
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSlate
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.GateDirection
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** "07:42" -> "7:42 AM". */
internal fun displayTime(hhmm: String): String =
    runCatching { LocalTime.parse(hhmm).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault(hhmm)

/** "2026-09-25" -> "Fri, 25 Sep 2026". */
internal fun displayDate(isoDate: String): String =
    runCatching { LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) }.getOrDefault(isoDate)

/** Teal for coming in, blue for going out - used everywhere a direction is shown. */
internal fun directionColor(direction: String): Color =
    if (direction == GateScanEntity.DIRECTION_IN) BrandPrimary else AccentBlue

internal fun GateDirection.color(): Color = directionColor(apiValue)

internal fun directionIcon(direction: String): ImageVector =
    if (direction == GateScanEntity.DIRECTION_IN) Icons.Filled.Login else Icons.Filled.Logout

internal fun directionLabel(direction: String): String =
    if (direction == GateScanEntity.DIRECTION_IN) GateDirection.IN.label else GateDirection.OUT.label

/** Pending Sync -> Synchronizing -> Synced, as the gate attendant sees it. */
internal fun syncLabel(scan: GateScanEntity, isSyncing: Boolean): Pair<String, Color> = when {
    scan.syncStatus == GateScanEntity.SYNC_SYNCED -> "Synced" to BrandPrimary
    scan.syncStatus == GateScanEntity.SYNC_FAILED -> "Sync failed" to AccentRed
    isSyncing -> "Synchronizing" to AccentSlate
    else -> "Pending Sync" to AccentGold
}

/** "Late 22 min" - or null when the scan wasn't late. */
internal fun lateLabel(scan: GateScanEntity): String? =
    if (scan.late) "Late" + (scan.minutesLate?.let { " $it min" } ?: "") else null

/** A small "RFID ✓" / "Face ✗" mark. */
@Composable
internal fun CheckMark(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    val color = if (ok) BrandPrimary else AccentRed
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (ok) "$label verified" else "$label not verified",
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
internal fun SmallChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(percent = 50)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * One gate record: "Juan Dela Cruz | BSIT-1A | Coming In | 7:42 AM | RFID ✓ |
 * Face ✓ | Synced". A failed face check shows why, and "Not recorded" in
 * place of a sync status - it was never attendance.
 */
@Composable
internal fun GateRecordRow(scan: GateScanEntity, isSyncing: Boolean, showDate: Boolean = false) {
    val recorded = scan.outcome == GateScanEntity.OUTCOME_RECORDED
    val dirColor = directionColor(scan.direction)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            InitialsAvatar(scan.studentName ?: scan.studentCode, if (recorded) dirColor else AccentRed)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        scan.studentName ?: scan.studentCode,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        displayTime(scan.scanTime),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    listOfNotNull("ID ${scan.studentCode}", scan.sectionName, if (showDate) displayDate(scan.scanDate) else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallChip(directionLabel(scan.direction), dirColor)
                    CheckMark("RFID", scan.rfidVerified)
                    CheckMark("Face", scan.verifiedByFace)
                }
                Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (recorded) {
                        lateLabel(scan)?.let { SmallChip(it, AccentGold, Modifier.padding(end = 8.dp)) }
                        val (label, color) = syncLabel(scan, isSyncing)
                        SmallChip(label, color)
                    } else {
                        SmallChip("Not recorded", AccentRed)
                    }
                }
                val note = if (recorded) scan.errorMessage.takeIf { scan.syncStatus == GateScanEntity.SYNC_FAILED } else scan.failureReason
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
