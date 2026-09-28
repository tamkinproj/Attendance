package com.muslimedu.attendance.ui.screens.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.repository.GateSummaryPeriod
import com.muslimedu.attendance.data.repository.GateSummaryRange
import com.muslimedu.attendance.data.repository.StudentGateSummary
import com.muslimedu.attendance.ui.components.EmptyState
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.components.StatChip
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentRedContainer
import com.muslimedu.attendance.ui.theme.AccentSlate
import com.muslimedu.attendance.ui.theme.AccentSlateContainer
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandPrimaryContainer
import com.muslimedu.attendance.viewmodel.GateSummaryViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/**
 * A week/month roll-up of this device's own gate records: which students
 * were present/late how many times, and how many face checks failed here.
 * Built from local `gate_scans` only - see [GateSummaryViewModel] and
 * [com.muslimedu.attendance.data.repository.GateSummary]'s own doc comments
 * for why a school with more than one gate phone should check the web's
 * Gate Reports page for the combined, cross-device totals instead.
 */
@Composable
fun GateSummaryScreen(viewModel: GateSummaryViewModel = hiltViewModel()) {
    val period by viewModel.period.collectAsState()
    val range by viewModel.range.collectAsState()
    val canStepForward by viewModel.canStepForward.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val isCurrentPeriod = !range.end.isBefore(LocalDate.now()) && !range.start.isAfter(LocalDate.now())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GateSummaryPeriod.entries.forEach { option ->
                    FilterChip(
                        selected = option == period,
                        onClick = { viewModel.selectPeriod(option) },
                        label = { Text(if (option == GateSummaryPeriod.Week) "Week" else "Month") },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = viewModel::stepBack) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous ${period.name.lowercase()}")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rangeLabel(period, range), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!isCurrentPeriod) {
                        TextButton(onClick = viewModel::goToToday) { Text("Jump to today") }
                    }
                }
                IconButton(onClick = viewModel::stepForward, enabled = canStepForward) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next ${period.name.lowercase()}")
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip(summary.studentDaysPresent, "Present", BrandPrimary, BrandPrimaryContainer, Modifier.weight(1f))
                        StatChip(summary.studentDaysLate, "Late", AccentGold, AccentGoldContainer, Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip(summary.faceFailedCount, "Face failed", AccentRed, AccentRedContainer, Modifier.weight(1f))
                        StatChip(summary.daysWithActivity, "Days used", AccentSlate, AccentSlateContainer, Modifier.weight(1f))
                    }
                }
            }
            Text(
                "This device's own records only - a school with more than one gate " +
                    "phone should check the web's Gate Reports page for the combined totals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (summary.students.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.EventBusy,
                    title = "Nothing recorded here for this period",
                    message = "Present and late counts will appear as students scan at this gate.",
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        } else {
            items(summary.students, key = { it.code }) { StudentSummaryRow(it) }
        }
    }
}

private fun rangeLabel(period: GateSummaryPeriod, range: GateSummaryRange): String = when (period) {
    GateSummaryPeriod.Month -> range.start.format(MONTH_FORMAT)
    GateSummaryPeriod.Week -> "${range.start.format(DAY_FORMAT)} - ${range.end.format(DAY_FORMAT)}"
}

@Composable
private fun StudentSummaryRow(student: StudentGateSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(student.name ?: student.code, BrandPrimary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(student.name ?: student.code, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull("ID ${student.code}", student.section).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallChip("${student.daysPresent} present", BrandPrimary)
                if (student.daysLate > 0) SmallChip("${student.daysLate} late", AccentGold)
            }
        }
    }
}
