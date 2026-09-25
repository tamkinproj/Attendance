package com.muslimedu.attendance.ui.screens.gate

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.viewmodel.GateHistoryFilter
import com.muslimedu.attendance.viewmodel.GateHistoryViewModel
import java.time.LocalDate

/** Every RFID record at this gate, one day at a time, with RFID/face/sync status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GateHistoryScreen(viewModel: GateHistoryViewModel = hiltViewModel()) {
    val dates by viewModel.dates.collectAsState()
    val date by viewModel.date.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val records by viewModel.records.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dates.forEach { day ->
                    FilterChip(
                        selected = day == date,
                        onClick = { viewModel.selectDate(day) },
                        label = { Text(if (day == LocalDate.now().toString()) "Today" else displayDate(day)) },
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GateHistoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = option == filter,
                        onClick = { viewModel.selectFilter(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            val recorded = records.count { it.outcome == GateScanEntity.OUTCOME_RECORDED }
            Text(
                "${displayDate(date)} · $recorded recorded" +
                    (records.size - recorded).takeIf { it > 0 }?.let { " · $it failed face check(s)" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (records.isEmpty()) {
                Text(
                    "Nothing recorded here for this day.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        items(records, key = { it.id }) { scan -> GateRecordRow(scan, isSyncing) }
    }
}
