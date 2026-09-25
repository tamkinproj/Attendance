package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.repository.GateSchedule
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.GateScheduleViewModel

private const val MORNING_ONLY = 1
private const val WHOLE_DAY = 2

/**
 * Set before the gate can be used: how many times each student comes in
 * and goes out per day. Scans alternate Coming In / Going Out; one more
 * than this is refused at the gate.
 */
@Composable
fun GateScheduleScreen(onSaved: () -> Unit, viewModel: GateScheduleViewModel = hiltViewModel()) {
    val saved by viewModel.scansPerDay.collectAsState()
    var perDay by rememberSaveable { mutableIntStateOf(saved ?: MORNING_ONLY) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How many times does each student pass the gate per day?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (saved == null) "The RFID gate can be used once this is set." else "Currently $saved Coming In and $saved Going Out per day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ScheduleOption(
                title = "Morning only",
                detail = "1 Coming In, 1 Going Out - e.g. a morning class that ends before lunch",
                selected = perDay == MORNING_ONLY,
                onClick = { perDay = MORNING_ONLY },
            )
            ScheduleOption(
                title = "Whole day",
                detail = "2 Coming In, 2 Going Out - e.g. out and back in for lunch",
                selected = perDay == WHOLE_DAY,
                onClick = { perDay = WHOLE_DAY },
            )
            ScheduleOption(
                title = "Custom",
                detail = "$perDay Coming In, $perDay Going Out",
                selected = perDay > WHOLE_DAY,
                onClick = { if (perDay <= WHOLE_DAY) perDay = WHOLE_DAY + 1 },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    // Custom is 3+: 1 and 2 are the presets above.
                    FilledTonalIconButton(onClick = { perDay = (perDay - 1).coerceAtLeast(WHOLE_DAY + 1) }, enabled = perDay > WHOLE_DAY + 1) {
                        Icon(Icons.Filled.Remove, contentDescription = "Fewer")
                    }
                    Text("$perDay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                    FilledTonalIconButton(onClick = { perDay = (perDay + 1).coerceAtMost(GateSchedule.MAX_PER_DAY) }, enabled = perDay < GateSchedule.MAX_PER_DAY) {
                        Icon(Icons.Filled.Add, contentDescription = "More")
                    }
                    Text("each way", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }

            Text(
                "At the gate, scans take turns: Coming In, then Going Out. A student who scans the same way twice, " +
                    "or more than $perDay time(s) each way in a day, is not recorded again. Going Out still works if they " +
                    "forgot to scan in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    viewModel.save(perDay)
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (saved == null) "Save and open the gate" else "Save") }
        }
    }
}

@Composable
private fun ScheduleOption(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) BrandPrimary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected && extra != null) extra()
            }
        }
    }
}
