package com.muslimedu.attendance.ui.screens.admin

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.ui.components.EmptyState
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSuccess
import com.muslimedu.attendance.viewmodel.StudentListViewModel
import com.muslimedu.attendance.viewmodel.StudentRow

private enum class StudentFilter(val label: String) {
    All("All"),
    Rfid("RFID"),
    Face("Face Enrolled"),
    Local("Local"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentListScreen(
    onRegisterFace: (StudentEntity) -> Unit,
    onAssignCard: (StudentEntity) -> Unit,
    viewModel: StudentListViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val isAddingStudent by viewModel.isAddingStudent.collectAsState()
    val addState by viewModel.addState.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(StudentFilter.All) }

    // This ViewModel is created once and outlives navigating away and back
    // (no back stack backs it, same as the enrollment screens), and its rows
    // are only ever loaded once in init - without this, a student synced from
    // the backend (or added locally) after this screen was first opened would
    // never show up here without an app restart.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val filteredRows = remember(rows, query, filter) {
        rows.filter { row ->
            val matchesQuery = query.isBlank() ||
                row.student.name.contains(query, ignoreCase = true) ||
                row.student.code.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                StudentFilter.All -> true
                StudentFilter.Rfid -> row.student.rfidCardNumber != null
                StudentFilter.Face -> row.hasFace
                StudentFilter.Local -> row.student.isLocalOnly
            }
            matchesQuery && matchesFilter
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAddStudentDialog) {
                Icon(Icons.Filled.Add, contentDescription = "Add Student")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search students...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(modifier = Modifier.padding(top = 8.dp)) {
                items(StudentFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            if (rows.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Person,
                    title = "No students yet",
                    message = "Download them in Sync & Account, or add one.",
                    modifier = Modifier.padding(top = 32.dp),
                )
            } else if (filteredRows.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "No matching students",
                    modifier = Modifier.padding(top = 32.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                    items(filteredRows, key = { it.student.id }) { row ->
                        StudentListRow(
                            row,
                            onRegisterFace = { onRegisterFace(row.student) },
                            onAssignCard = { onAssignCard(row.student) },
                            loadPhoto = { viewModel.loadPhoto(row.student) },
                        )
                    }
                }
            }
        }
    }

    if (isAddingStudent) {
        AddStudentDialog(
            isSaving = addState.isSaving,
            error = addState.error,
            onDismiss = viewModel::dismissAddStudentDialog,
            onSave = viewModel::addStudent,
        )
    }
}

@Composable
private fun StudentListRow(
    row: StudentRow,
    onRegisterFace: () -> Unit,
    onAssignCard: () -> Unit,
    loadPhoto: suspend () -> Bitmap?,
) {
    val student = row.student
    val photo by produceState<Bitmap?>(initialValue = null, student.id) { value = loadPhoto() }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cachedPhoto = photo
            if (cachedPhoto != null) {
                Image(
                    bitmap = cachedPhoto.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(student.name, fontWeight = FontWeight.Medium)
                    if (student.isLocalOnly) {
                        Text(
                            text = " (local)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGold,
                        )
                    }
                }
                Text(student.code, style = MaterialTheme.typography.bodySmall)
                student.sectionName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            val hasCard = student.rfidCardNumber != null
            IconButton(onClick = onAssignCard) {
                Icon(
                    Icons.Filled.CreditCard,
                    contentDescription = if (hasCard) "Re-assign RFID card" else "Assign RFID card",
                    tint = if (hasCard) AccentSuccess else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onRegisterFace) {
                Icon(
                    Icons.Filled.Face,
                    contentDescription = if (row.hasFace) "Re-register face" else "Register face",
                    tint = if (row.hasFace) AccentSuccess else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun AddStudentDialog(
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, code: String, sectionName: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Add Student") },
        text = {
            Column {
                Text(
                    "Use the student's real school code - their gate scans upload " +
                        "under this code, and the server rejects a code it doesn't know. " +
                        "A later student download replaces these details with the server's.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Student Code") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = section,
                    onValueChange = { section = it },
                    label = { Text("Section (optional)") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (error != null) {
                    Text(text = error, color = AccentRed, modifier = Modifier.padding(top = 8.dp))
                }
                if (isSaving) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, code, section) },
                enabled = !isSaving && name.isNotBlank() && code.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        },
    )
}
