package com.muslimedu.attendance.ui.screens.admin

import android.app.TimePickerDialog
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
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.repository.GateSchedule
import com.muslimedu.attendance.ui.theme.AccentBlue
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.GateScheduleViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MORNING_ONLY = 1
private const val WHOLE_DAY = 2
private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * Set before the gate can be used: how many times each student comes in
 * and goes out per day, and when each Coming In / Going Out opens. Before
 * its time a direction stays locked on the gate dashboard and a student's
 * scan for it is refused; it opens by itself at that time.
 */
@Composable
fun GateScheduleScreen(onSaved: () -> Unit, viewModel: GateScheduleViewModel = hiltViewModel()) {
    val saved by viewModel.schedule.collectAsState()
    val context = LocalContext.current

    var perDay by rememberSaveable { mutableIntStateOf(saved?.perDay ?: MORNING_ONLY) }
    // Times are stored as minutes of the day so they survive recreation.
    var inMinutes by rememberSaveable { mutableStateOf(initialMinutes(saved?.inTimes, perDay, isIn = true)) }
    var outMinutes by rememberSaveable { mutableStateOf(initialMinutes(saved?.outTimes, perDay, isIn = false)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun choose(count: Int) {
        perDay = count
        val keep = saved?.takeIf { it.perDay == count }
        inMinutes = initialMinutes(keep?.inTimes, count, isIn = true)
        outMinutes = initialMinutes(keep?.outTimes, count, isIn = false)
        error = null
    }

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> onPicked(hour * 60 + minute) }, current / 60, current % 60, false).show()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How many times does each student pass the gate per day?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (saved == null) "The RFID gate can be used once this is set." else "Currently ${saved?.perDay} Coming In and ${saved?.perDay} Going Out per day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ScheduleOption(
                title = "Morning only",
                detail = "1 Coming In, 1 Going Out - e.g. a morning class that ends before lunch",
                selected = perDay == MORNING_ONLY,
                onClick = { choose(MORNING_ONLY) },
            )
            ScheduleOption(
                title = "Whole day",
                detail = "2 Coming In, 2 Going Out - e.g. out and back in for lunch",
                selected = perDay == WHOLE_DAY,
                onClick = { choose(WHOLE_DAY) },
            )
            ScheduleOption(
                title = "Custom",
                detail = "$perDay Coming In, $perDay Going Out",
                selected = perDay > WHOLE_DAY,
                onClick = { if (perDay <= WHOLE_DAY) choose(WHOLE_DAY + 1) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    // Custom is 3+: 1 and 2 are the presets above.
                    FilledTonalIconButton(onClick = { choose(perDay - 1) }, enabled = perDay > WHOLE_DAY + 1) {
                        Icon(Icons.Filled.Remove, contentDescription = "Fewer")
                    }
                    Text("$perDay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                    FilledTonalIconButton(onClick = { choose(perDay + 1) }, enabled = perDay < GateSchedule.MAX_PER_DAY) {
                        Icon(Icons.Filled.Add, contentDescription = "More")
                    }
                    Text("each way", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }

            Text(
                "Opening times",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Each scan opens at its time: before it, that button stays locked on the gate and appears by itself when " +
                    "the time comes. Tap a time to change it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    for (i in 0 until perDay) {
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TimeRow(Icons.Filled.Login, BrandPrimary, "Coming In ${i + 1}", inMinutes.getOrElse(i) { 0 }) {
                            pickTime(inMinutes[i]) { picked -> inMinutes = inMinutes.toMutableList().also { it[i] = picked }; error = null }
                        }
                        TimeRow(Icons.Filled.Logout, AccentBlue, "Going Out ${i + 1}", outMinutes.getOrElse(i) { 0 }) {
                            pickTime(outMinutes[i]) { picked -> outMinutes = outMinutes.toMutableList().also { it[i] = picked }; error = null }
                        }
                    }
                }
            }

            Text(
                "At the gate, scans take turns: Coming In, then Going Out. A student who scans the same way twice, " +
                    "or more than $perDay time(s) each way in a day, is not recorded again. Going Out still works if they " +
                    "forgot to scan in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = AccentRed, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    val ok = viewModel.save(perDay, inMinutes.map(::toTime), outMinutes.map(::toTime))
                    if (ok) onSaved() else error = "Each time must be later than the one before it (Coming In 1, Going Out 1, Coming In 2...)."
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (saved == null) "Save and open the gate" else "Save") }
        }
    }
}

private fun toTime(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)

/** Saved times when they match [perDay], otherwise the defaults for it - as minutes of the day. */
private fun initialMinutes(saved: List<LocalTime>?, perDay: Int, isIn: Boolean): List<Int> {
    val times = saved?.takeIf { it.size == perDay }
        ?: GateSchedule.defaultTimes(perDay).let { if (isIn) it.first else it.second }
    return times.map { it.hour * 60 + it.minute }
}

@Composable
private fun TimeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, label: String, minutes: Int, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        TextButton(onClick = onClick) {
            Text("opens ${toTime(minutes).format(DISPLAY_TIME)}", fontWeight = FontWeight.SemiBold, color = color)
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
