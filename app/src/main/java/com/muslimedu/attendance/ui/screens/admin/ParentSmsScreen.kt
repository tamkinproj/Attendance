package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.theme.AccentBlue
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentGoldContainer
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandPrimaryContainer
import com.muslimedu.attendance.util.SmsTemplate
import com.muslimedu.attendance.viewmodel.ParentSmsUiState
import com.muslimedu.attendance.viewmodel.ParentSmsViewModel
import java.time.LocalDate

/** Placeholder -> the chip label an admin understands. */
private val PLACEHOLDER_LABELS = listOf(
    "{student}" to "Student name",
    "{time}" to "Time",
    "{date}" to "Date",
    "{code}" to "Student ID",
    "{school}" to "School",
)
private val LATE_PLACEHOLDER = "{minutes_late}" to "Minutes late"

/**
 * Admin > Parent SMS: the text a parent gets when their child scans at the
 * gate, e.g. "Ang inyong anak na si Malik Aziz ay pumasok sa paaralan ng
 * 7:42 AM (Sep 26, 2026)." One message for Coming In, one for Going Out,
 * each with a live preview. Needs a connection - the school server sends
 * the texts and keeps the wording.
 */
@Composable
fun ParentSmsScreen(viewModel: ParentSmsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { viewModel.load() }

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
            Text(
                "Parents get a text each time their child scans Coming In or Going Out at the gate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumbersCard(state)
            when {
                state.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = BrandPrimary) }
                state.loadError != null -> LoadError(state.loadError!!, onRetry = viewModel::load)
                else -> {
                    SmsSwitchCard(state.smsEnabled)
                    MessageEditor(
                        title = "Coming In",
                        icon = Icons.Filled.Login,
                        color = BrandPrimary,
                        text = state.inTemplate,
                        isDefault = state.inTemplate.trim() == state.defaultIn,
                        maxLength = state.maxLength,
                        preview = SmsTemplate.render(state.inTemplate, state.sampleName, state.sampleCode, "07:42", today(), state.schoolName),
                        onChange = viewModel::onInChange,
                        onReset = viewModel::resetIn,
                    )
                    MessageEditor(
                        title = "Going Out",
                        icon = Icons.Filled.Logout,
                        color = AccentBlue,
                        text = state.outTemplate,
                        isDefault = state.outTemplate.trim() == state.defaultOut,
                        maxLength = state.maxLength,
                        preview = SmsTemplate.render(state.outTemplate, state.sampleName, state.sampleCode, "15:10", today(), state.schoolName),
                        onChange = viewModel::onOutChange,
                        onReset = viewModel::resetOut,
                    )
                    state.lateTemplate?.let { lateText ->
                        MessageEditor(
                            title = "Late Coming In",
                            icon = Icons.Filled.AlarmOn,
                            color = AccentGold,
                            text = lateText,
                            isDefault = lateText.trim() == state.defaultLate,
                            maxLength = state.maxLength,
                            preview = SmsTemplate.render(lateText, state.sampleName, state.sampleCode, "07:52", today(), state.schoolName, 22),
                            onChange = viewModel::onLateChange,
                            onReset = viewModel::resetLate,
                            note = "Sent instead of the Coming In text when the scan is after its \"Late after\" time (Admin > Gate Schedule).",
                            extraPlaceholders = listOf(LATE_PLACEHOLDER),
                        )
                    }
                    state.absentTemplate?.let { absentText ->
                        MessageEditor(
                            title = "Not arrived",
                            icon = Icons.Filled.Schedule,
                            color = AccentRed,
                            text = absentText,
                            isDefault = absentText.trim() == state.defaultAbsent,
                            maxLength = state.maxLength,
                            preview = SmsTemplate.render(absentText, state.sampleName, state.sampleCode, "09:00", today(), state.schoolName),
                            onChange = viewModel::onAbsentChange,
                            onReset = viewModel::resetAbsent,
                            note = "Sent at the not-arrived time (Admin > Gate Schedule) to parents of students who haven't scanned. {time} is that time.",
                        )
                    }
                    state.saveError?.let {
                        Text(it, color = AccentRed, modifier = Modifier.padding(top = 16.dp))
                    }
                    state.savedNotice?.let {
                        Text(it, color = BrandPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
                    }
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.save()
                        },
                        enabled = state.changed && !state.saving,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Text(if (state.changed) "Save messages" else "Saved")
                        }
                    }
                }
            }
        }
    }
}

private fun today(): String = LocalDate.now().toString()

/** How many students a text can reach - numbers are entered in the Register wizard (step 4) or Students. */
@Composable
private fun NumbersCard(state: ParentSmsUiState) {
    val stats = state.stats
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.People, contentDescription = null, tint = BrandPrimary)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    "${stats.withNumber} of ${stats.total} students have a parent number",
                    fontWeight = FontWeight.SemiBold,
                )
                val missing = stats.total - stats.withNumber
                Text(
                    buildString {
                        append(if (missing > 0) "$missing get no texts yet. " else "Every student's parent can get texts. ")
                        if (stats.notSent > 0) append("${stats.notSent} waiting to upload. ")
                        if (stats.refused > 0) append("${stats.refused} refused by the server - see Students. ")
                        append("Add numbers in Register (step 4) or Students > Parent no.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stats.refused > 0) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SmsSwitchCard(enabled: Boolean?) {
    val on = enabled == true
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        color = if (on) BrandPrimaryContainer else AccentGoldContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (on) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (on) BrandPrimary else AccentGold,
            )
            Text(
                if (on) {
                    "Texts are on - the server sends one per Coming In and Going Out."
                } else {
                    "Texts are switched off on the server. A superadmin turns them on in SMS Gateway settings on the web - until then no text goes out."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (on) BrandPrimary else AccentGold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = AccentGold, modifier = Modifier.size(48.dp))
        Text(message, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageEditor(
    title: String,
    icon: ImageVector,
    color: Color,
    text: String,
    isDefault: Boolean,
    maxLength: Int,
    preview: String,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
    note: String? = null,
    extraPlaceholders: List<Pair<String, String>> = emptyList(),
) {
    // A TextFieldValue so a placeholder chip goes in where the cursor is.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Loaded from the server, reset to the default, or trimmed to the limit.
    LaunchedEffect(text) {
        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    }

    SectionHeader(title, modifier = Modifier.padding(top = 24.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color)
                Text(
                    "Text sent when a student scans $title",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (!isDefault) TextButton(onClick = onReset) { Text("Use default") }
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    onChange(it.text)
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                supportingText = {
                    val length = preview.length
                    Text(
                        "${text.length}/$maxLength · preview is $length characters = ${SmsTemplate.segments(preview)} SMS",
                        color = if (SmsTemplate.segments(preview) > 1) AccentGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                isError = text.isNotBlank() && !text.contains("{student}"),
            )
            Text(
                "Tap to insert:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (PLACEHOLDER_LABELS + extraPlaceholders).forEach { (placeholder, label) ->
                    AssistChip(
                        onClick = {
                            val start = field.selection.min
                            val end = field.selection.max
                            val inserted = field.text.replaceRange(start, end, placeholder).take(maxLength)
                            field = TextFieldValue(inserted, TextRange((start + placeholder.length).coerceAtMost(inserted.length)))
                            onChange(inserted)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Preview",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            SmsBubble(preview.ifBlank { "(empty - the default message is used)" })
        }
    }
}

/** Looks like a received text, so the admin reads it the way a parent will. */
@Composable
private fun SmsBubble(message: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Sms, contentDescription = null, tint = BrandPrimary, modifier = Modifier.padding(top = 6.dp).size(20.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
    }
}
