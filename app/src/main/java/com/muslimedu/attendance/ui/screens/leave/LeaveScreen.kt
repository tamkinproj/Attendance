package com.muslimedu.attendance.ui.screens.leave

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.ui.theme.AccentGold
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val LEAVE_TYPES = listOf("Casual Leave", "Sick Leave", "Emergency Leave", "Other")

/**
 * Matches the "Apply for Leave" screen from the Atten+ design mockup the
 * user supplied. Preview-only for now: this app doesn't call backend
 * endpoints that haven't been confirmed against real Laravel source (see
 * CLAUDE.md's "Confirmed against the real backend" discipline), and no
 * leave endpoint exists there yet. Submitting shows a local "not synced"
 * notice instead of calling any repository/API - nothing here is persisted
 * or sent anywhere. Swap the `submitted = true` submit handler below for a
 * real LeaveRepository call once the backend adds a leave endpoint; the
 * form/validation shape here is meant to survive that change as-is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveScreen(
    applicantName: String,
    designation: String,
) {
    var leaveType by remember { mutableStateOf(LEAVE_TYPES.first()) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val datePicker = remember {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                selectedDateMillis = Calendar.getInstance().apply { set(year, month, dayOfMonth) }.timeInMillis
                submitted = false
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Apply for Leave", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Preview only - leave requests aren't sent to the school server yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )

            OutlinedTextField(
                value = applicantName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Applicant Name") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = designation,
                onValueChange = {},
                readOnly = true,
                label = { Text("Designation") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            ExposedDropdownMenuBox(
                expanded = typeMenuExpanded,
                onExpandedChange = { typeMenuExpanded = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                OutlinedTextField(
                    value = leaveType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Leave Type") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = typeMenuExpanded,
                    onDismissRequest = { typeMenuExpanded = false },
                ) {
                    LEAVE_TYPES.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type) },
                            onClick = {
                                leaveType = type
                                typeMenuExpanded = false
                                submitted = false
                            },
                        )
                    }
                }
            }

            // A readOnly OutlinedTextField still intercepts touch for cursor
            // placement, so a plain readOnly + clickable won't reliably open
            // the dialog - disabling it instead (with colors overridden so it
            // doesn't look greyed out) lets the wrapping Box's click through.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable { datePicker.show() },
            ) {
                OutlinedTextField(
                    value = selectedDateMillis?.let { dateFormat.format(Date(it)) } ?: "",
                    onValueChange = {},
                    enabled = false,
                    label = { Text("Date") },
                    trailingIcon = { Icon(Icons.Filled.CalendarToday, contentDescription = null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it; submitted = false },
                label = { Text("Reason") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Button(
                onClick = { submitted = true },
                enabled = selectedDateMillis != null && reason.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Text("Submit Request")
            }

            if (submitted) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentGold.copy(alpha = 0.12f)),
                ) {
                    Text(
                        "This is a preview of the Leave screen - nothing was sent. " +
                            "Leave requests will sync to the school server once the backend adds support for them.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
