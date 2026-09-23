package com.muslimedu.attendance.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.components.QuickActionCard
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.BrandPurple
import com.muslimedu.attendance.ui.theme.BrandPurpleLight
import com.muslimedu.attendance.viewmodel.TeacherDashboardStats
import com.muslimedu.attendance.viewmodel.TeacherDashboardViewModel

/**
 * The teacher's home screen - the real gap this closes is that
 * [com.muslimedu.attendance.ui.screens.scanner.RfidScanScreen] used to be
 * shown directly the moment a roster synced, with no summary of the day or
 * way to get anywhere except scan. This sits in front of it.
 */
@Composable
fun TeacherDashboardScreen(
    onScanAttendance: () -> Unit,
    onMyClasses: () -> Unit,
    onClassRoster: () -> Unit,
    onApplyLeave: () -> Unit,
    viewModel: TeacherDashboardViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsState()
    val readerStatus by viewModel.readerStatus.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    // Re-read on every visit, not just the first: coming back here after
    // scanning a few students should show the new "Present" count, and this
    // ViewModel outlives navigating away (no back stack backs it).
    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            when (val current = stats) {
                null -> CircularProgressIndicator()
                else -> {
                    Text(
                        text = "As-salamu Alaykum",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = current.teacherName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = current.today,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )

                    AttendanceSummaryCard(current)
                }
            }

            StatusCard(readerStatus = readerStatus, isOnline = isOnline, modifier = Modifier.padding(top = 20.dp))

            SectionHeader("Quick Actions", modifier = Modifier.padding(top = 24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionCard(
                    icon = Icons.Filled.CreditCard,
                    label = "Scan Attendance",
                    color = BrandPurple,
                    onClick = onScanAttendance,
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    icon = Icons.Filled.School,
                    label = "My Classes",
                    color = AccentGold,
                    onClick = onMyClasses,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionCard(
                    icon = Icons.Filled.Groups,
                    label = "Class Roster",
                    color = AccentTeal,
                    onClick = onClassRoster,
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    icon = Icons.Filled.EventBusy,
                    label = "Apply for Leave",
                    color = AccentRed,
                    onClick = onApplyLeave,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A purple-gradient hero card - the plain white stat card this replaced didn't carry any of the app's brand identity on its most-seen screen. */
@Composable
private fun AttendanceSummaryCard(stats: TeacherDashboardStats) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(BrandPurple, BrandPurpleLight)),
                shape = MaterialTheme.shapes.large,
            )
            .padding(20.dp),
    ) {
        Column {
            Text(
                "Today's Attendance",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeroStatChip(stats.presentToday, "Present", Modifier.weight(1f))
                HeroStatChip(stats.remainingInRoster, "Not Yet Scanned", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStatChip(value: Int, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

/** Reader + network status combined into one card with a leading status dot each, replacing the two separate pill badges this screen used to stack. */
@Composable
private fun StatusCard(readerStatus: ReaderStatus, isOnline: Boolean, modifier: Modifier = Modifier) {
    val readerColor = when {
        readerStatus.connected -> AccentTeal
        readerStatus.canSimulate -> AccentGold
        else -> AccentRed
    }
    val readerLabel = when {
        readerStatus.connected -> readerStatus.deviceName?.let { "Reader connected - $it" } ?: "Reader connected"
        readerStatus.canSimulate -> "No reader - use Simulate Scan"
        else -> "No reader detected"
    }
    val networkColor = if (isOnline) AccentTeal else AccentGold
    val networkLabel = if (isOnline) "Online - syncing" else "Offline - scans will sync later"

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusDotRow(readerColor, readerLabel)
            StatusDotRow(networkColor, networkLabel, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun StatusDotRow(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

