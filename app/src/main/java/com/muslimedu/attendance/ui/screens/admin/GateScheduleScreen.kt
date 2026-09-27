package com.muslimedu.attendance.ui.screens.admin

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.muslimedu.attendance.data.repository.GateScheduleConfig
import com.muslimedu.attendance.ui.theme.AccentBlue
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.viewmodel.AbsenceSettings
import com.muslimedu.attendance.viewmodel.AbsenceSettingsState
import com.muslimedu.attendance.viewmodel.GateScheduleViewModel
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MORNING_ONLY = 1
private const val WHOLE_DAY = 2
private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** No late check for that Coming In, in the saveable minutes list. */
private const val OFF = -1

/**
 * Set before the gate can be used: how many times each student comes in
 * and goes out per day, and when each Coming In / Going Out opens. Before
 * its time a direction stays locked on the gate dashboard and a student's
 * scan for it is refused; it opens by itself at that time.
 *
 * Each Coming In also has a "Late after" time (or none): a Coming In scanned
 * after it is recorded as Late.
 *
 * Below, the school's "not arrived" alert (kept on the school server, which
 * sends those texts): the time after which students with no scan are listed
 * on the web and, if chosen, their parents texted, and the school days.
 */
@Composable
fun GateScheduleScreen(onSaved: () -> Unit, viewModel: GateScheduleViewModel = hiltViewModel()) {
    val saved by viewModel.schedule.collectAsState()
    val context = LocalContext.current

    var perDay by rememberSaveable { mutableIntStateOf(saved?.perDay ?: MORNING_ONLY) }
    // Times are stored as minutes of the day so they survive recreation.
    var inMinutes by rememberSaveable { mutableStateOf(initialMinutes(saved?.inTimes, perDay, isIn = true)) }
    var outMinutes by rememberSaveable { mutableStateOf(initialMinutes(saved?.outTimes, perDay, isIn = false)) }
    var lateMinutes by rememberSaveable { mutableStateOf(initialLate(saved?.takeIf { it.perDay == perDay }?.lateAfter, inMinutes, outMinutes)) }
    var error by remember { mutableStateOf<String?>(null) }

    // The not-arrived alert: loaded from the server, edited here, saved with the schedule.
    val absence by viewModel.absence.collectAsState()
    var absLoaded by rememberSaveable { mutableStateOf(false) }
    var absEnabled by rememberSaveable { mutableStateOf(false) }
    var absCutoff by rememberSaveable { mutableIntStateOf(OFF) }
    var absTexts by rememberSaveable { mutableStateOf(true) }
    var absDays by rememberSaveable { mutableStateOf(listOf(1, 2, 3, 4, 5)) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.loadAbsence() }
    LaunchedEffect(absence) {
        val loaded = (absence as? AbsenceSettingsState.Loaded)?.settings ?: return@LaunchedEffect
        if (absLoaded) return@LaunchedEffect
        absLoaded = true
        absEnabled = loaded.enabled
        absCutoff = loaded.cutoff?.let { it.hour * 60 + it.minute } ?: OFF
        absTexts = if (loaded.enabled) loaded.textParents else true
        absDays = loaded.schoolDays.sorted()
    }
    fun currentConfig() = GateScheduleConfig(
        perDay,
        inMinutes.map(::toTime),
        outMinutes.map(::toTime),
        lateMinutes.map { if (it == OFF) null else toTime(it) },
    )

    fun choose(count: Int) {
        perDay = count
        val keep = saved?.takeIf { it.perDay == count }
        inMinutes = initialMinutes(keep?.inTimes, count, isIn = true)
        outMinutes = initialMinutes(keep?.outTimes, count, isIn = false)
        lateMinutes = initialLate(keep?.lateAfter, inMinutes, outMinutes)
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
                        val late = lateMinutes.getOrElse(i) { OFF }
                        LateRow(
                            minutes = late,
                            onToggle = { on ->
                                val start = defaultLateMinutes(i, inMinutes[i], outMinutes[i])
                                lateMinutes = lateMinutes.toMutableList().also { it[i] = if (on) start else OFF }
                                error = null
                            },
                            onPick = {
                                pickTime(late) { picked -> lateMinutes = lateMinutes.toMutableList().also { it[i] = picked }; error = null }
                            },
                        )
                        TimeRow(Icons.Filled.Logout, AccentBlue, "Going Out ${i + 1}", outMinutes.getOrElse(i) { 0 }) {
                            pickTime(outMinutes[i]) { picked -> outMinutes = outMinutes.toMutableList().also { it[i] = picked }; error = null }
                        }
                    }
                }
            }

            Text(
                "Late after: a Coming In scanned after this time is recorded as Late - the gate, the history, the web " +
                    "and the parent's text say so. Switch it off for a Coming In that is never late.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "At the gate, scans take turns: Coming In, then Going Out. A student who scans the same way twice, " +
                    "or more than $perDay time(s) each way in a day, is not recorded again. Going Out still works if they " +
                    "forgot to scan in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AbsenceCard(
                state = absence,
                enabled = absEnabled,
                cutoffMinutes = absCutoff,
                textParents = absTexts,
                schoolDays = absDays,
                onEnable = { on ->
                    absEnabled = on
                    if (on && absCutoff == OFF) {
                        val config = currentConfig()
                        absCutoff = GateSchedule.defaultAbsenceCutoff(config.inTimes, config.outTimes, config.lateAfter).let { it.hour * 60 + it.minute }
                    }
                    error = null
                },
                onPickCutoff = { pickTime(absCutoff.takeIf { it != OFF } ?: 9 * 60) { picked -> absCutoff = picked; error = null } },
                onTextParents = { absTexts = it; error = null },
                onToggleDay = { day -> absDays = (if (day in absDays) absDays - day else absDays + day).sorted(); error = null },
                onRetry = viewModel::loadAbsence,
            )

            error?.let { Text(it, color = AccentRed, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    val config = currentConfig()
                    val alert = if (absence is AbsenceSettingsState.Loaded) {
                        AbsenceSettings(absEnabled, absCutoff.takeIf { it != OFF }?.let(::toTime), absTexts, absDays.toSet())
                    } else {
                        null
                    }
                    // The alert is checked against the new schedule before anything is saved.
                    val alertProblem = alert?.takeIf { it.enabled }?.let { a ->
                        a.cutoff?.let { GateSchedule.absenceCutoffProblem(it, config.inTimes, config.outTimes, config.lateAfter) }
                            ?: if (a.schoolDays.isEmpty()) "Choose at least one school day for the not-arrived alert." else null
                    }
                    val problem = alertProblem ?: viewModel.save(perDay, config.inTimes, config.outTimes, config.lateAfter)
                    when {
                        problem != null -> error = problem
                        alert == null -> onSaved()
                        else -> scope.launch {
                            saving = true
                            val alertError = viewModel.saveAbsence(alert, config)
                            saving = false
                            // The schedule itself is saved on the phone either way.
                            if (alertError == null) onSaved() else error = "Gate schedule saved. $alertError"
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(if (saved == null) "Save and open the gate" else "Save")
                }
            }
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

/** Saved late times when they match, the defaults for a new schedule; minutes of the day, [OFF] for none. */
private fun initialLate(saved: List<LocalTime?>?, inMinutes: List<Int>, outMinutes: List<Int>): List<Int> =
    saved?.takeIf { it.size == inMinutes.size }?.map { it?.let { t -> t.hour * 60 + t.minute } ?: OFF }
        ?: GateSchedule.defaultLateAfter(inMinutes.map(::toTime), outMinutes.map(::toTime)).map { it?.let { t -> t.hour * 60 + t.minute } ?: OFF }

/** Where the switch starts a late time: the default for that Coming In, or its opening time when that doesn't fit. */
private fun defaultLateMinutes(index: Int, inMinute: Int, outMinute: Int): Int {
    val candidate = inMinute + if (index == 0) 90 else 30
    return if (candidate < outMinute && candidate < 24 * 60) candidate else inMinute
}

private val WEEKDAYS = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
private val WEEKDAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * The "not arrived" alert: after the time, students with a card and no scan
 * are listed on the web (Gate Students > Not arrived), and with "Text
 * parents" each parent gets one text - only once every gate phone has
 * uploaded its scans, never on a holiday, suspension or non-school day.
 */
@Composable
private fun AbsenceCard(
    state: AbsenceSettingsState,
    enabled: Boolean,
    cutoffMinutes: Int,
    textParents: Boolean,
    schoolDays: List<Int>,
    onEnable: (Boolean) -> Unit,
    onPickCutoff: () -> Unit,
    onTextParents: (Boolean) -> Unit,
    onToggleDay: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    Text("Not arrived alert", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                AbsenceSettingsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Loading from the school server...", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
                is AbsenceSettingsState.Unavailable -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
                }
                is AbsenceSettingsState.Loaded -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = if (enabled) AccentRed else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                        Text(
                            if (enabled) "Not arrived by" else "Off",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                        if (enabled && cutoffMinutes != OFF) {
                            TextButton(onClick = onPickCutoff) {
                                Text(toTime(cutoffMinutes).format(DISPLAY_TIME), fontWeight = FontWeight.SemiBold, color = AccentRed)
                            }
                        }
                        Switch(checked = enabled, onCheckedChange = onEnable, modifier = Modifier.padding(start = 4.dp))
                    }
                    if (enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Filled.Sms, contentDescription = null, tint = if (textParents) BrandPrimary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("Text parents", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "One text per student that day, e.g. \"...ay hindi pa pumapasok...\" (wording in Parent SMS).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = textParents, onCheckedChange = onTextParents, modifier = Modifier.padding(start = 4.dp))
                        }
                        Text("School days", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WEEKDAYS.forEach { (day, letter) ->
                                val on = day in schoolDays
                                Surface(
                                    onClick = { onToggleDay(day) },
                                    shape = CircleShape,
                                    color = if (on) BrandPrimary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            letter,
                                            fontWeight = FontWeight.Bold,
                                            color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            schoolDays.sorted().joinToString(", ") { WEEKDAY_NAMES[it - 1] }.ifEmpty { "No school days chosen" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
    Text(
        "After this time, students with a card who haven't scanned are listed on the web (Gate Students > Not arrived). " +
            "Texts go out only once every gate phone is online and has uploaded its scans, never on holidays or class " +
            "suspensions in the school calendar, and at most once per student a day.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Late after 7:30 AM" under a Coming In, with a switch; off = that Coming In is never late. */
@Composable
private fun LateRow(minutes: Int, onToggle: (Boolean) -> Unit, onPick: () -> Unit) {
    val on = minutes != OFF
    Row(modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.AlarmOn, contentDescription = null, tint = if (on) AccentGold else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        Text(
            if (on) "Late after" else "No late check",
            style = MaterialTheme.typography.bodyMedium,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        if (on) {
            TextButton(onClick = onPick) {
                Text(toTime(minutes).format(DISPLAY_TIME), fontWeight = FontWeight.SemiBold, color = AccentGold)
            }
        }
        Switch(checked = on, onCheckedChange = onToggle, modifier = Modifier.padding(start = 4.dp))
    }
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
