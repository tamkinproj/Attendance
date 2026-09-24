package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSuccess
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.remote.dto.AdminClassSummaryDto
import com.muslimedu.attendance.data.remote.dto.AdminSectionDto
import com.muslimedu.attendance.viewmodel.AdminDirectoryStudentRow
import com.muslimedu.attendance.viewmodel.AdminDirectoryUiState
import com.muslimedu.attendance.viewmodel.AdminDirectoryViewModel

/**
 * Admin: browse the whole school as Classes -> Sections -> Students -> one
 * Student's detail, all four levels live from the network
 * ([com.muslimedu.attendance.data.repository.AdminDirectoryRepository]) and
 * none of it cached locally the way the teacher-scoped roster is (see that
 * repository's doc comment for why).
 *
 * Deliberately one composable with its own internal back handling, not four
 * separate [com.muslimedu.attendance.ui.navigation.AppRoot] destinations:
 * that state machine's TopAppBar back arrow only ever returns to the Scan
 * screen (see AppRoot's own doc comment - "one level below the scan screen"
 * is deliberately as far as its back stack goes today), so a real four-level
 * drill-down needs its own in-screen back affordance regardless. Follows the
 * same convention [com.muslimedu.attendance.ui.screens.enrollment.RfidEnrollmentScreen]
 * and [com.muslimedu.attendance.ui.screens.enrollment.FaceEnrollmentScreen]
 * already use for their own internal Picker/Listening/Result steps.
 */
@Composable
fun AdminDirectoryScreen(
    onRegisterFace: (StudentEntity) -> Unit,
    onAssignCard: (StudentEntity) -> Unit,
    viewModel: AdminDirectoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Re-checks the currently-viewed student's local RFID/face status every
    // time this screen is (re)entered - most importantly right after
    // returning from assigning a card or enrolling a face, since both of
    // those hand off to a different AppRoot destination and back always
    // lands on the Scan screen, not back here (see this file's doc comment).
    // A no-op unless the state is actually StudentDetail.
    LaunchedEffect(Unit) { viewModel.refreshCurrentStudent() }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            is AdminDirectoryUiState.LoadingClasses -> LoadingContent("Loading classes...")
            is AdminDirectoryUiState.ClassesError -> ErrorContent(current.message, onRetry = viewModel::loadClasses)
            is AdminDirectoryUiState.Classes -> ClassesContent(current.classes, onSelect = viewModel::selectClass)

            is AdminDirectoryUiState.LoadingSections -> LoadingContent("Loading ${current.className}'s sections...")
            is AdminDirectoryUiState.SectionsError -> ErrorContent(
                current.message, onRetry = viewModel::loadClasses, onBack = viewModel::loadClasses, title = current.className,
            )
            is AdminDirectoryUiState.Sections -> SectionsContent(
                current.className, current.sections, onSelect = viewModel::selectSection, onBack = viewModel::backToClasses,
            )

            is AdminDirectoryUiState.LoadingStudents -> LoadingContent("Loading ${current.sectionName}'s students...")
            is AdminDirectoryUiState.StudentsError -> ErrorContent(
                current.message, onRetry = viewModel::backToSections, onBack = viewModel::backToSections, title = current.sectionName,
            )
            is AdminDirectoryUiState.Students -> StudentsContent(
                current.sectionName, current.rows, onSelect = viewModel::selectStudent, onBack = viewModel::backToSections,
            )

            is AdminDirectoryUiState.StudentDetail -> StudentDetailContent(
                current.row,
                onBack = viewModel::backToStudents,
                onRegisterFace = onRegisterFace,
                onAssignCard = onAssignCard,
            )
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(message, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
    title: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        Text("Couldn't load this", style = MaterialTheme.typography.titleLarge)
        Text(message, color = AccentRed, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) { Text("Retry") }
        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ClassesContent(classes: List<AdminClassSummaryDto>, onSelect: (AdminClassSummaryDto) -> Unit) {
    if (classes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No classes found for this school")
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Classes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(classes, key = { it.id }) { schoolClass ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    onClick = { onSelect(schoolClass) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(schoolClass.name, fontWeight = FontWeight.Medium)
                            schoolClass.gradeLevel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("${schoolClass.currentEnrollment} students", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionsContent(className: String, sections: List<AdminSectionDto>, onSelect: (AdminSectionDto) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(className, onBack)
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(sections, key = { it.id }) { section ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    onClick = { onSelect(section) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(section.name, fontWeight = FontWeight.Medium)
                        Text("${section.currentEnrollment} students", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentsContent(
    sectionName: String,
    rows: List<AdminDirectoryStudentRow>,
    onSelect: (AdminDirectoryStudentRow) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(sectionName, onBack)
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No students enrolled in this section")
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(rows, key = { it.student.id }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    onClick = { onSelect(row) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(row.student.name, fontWeight = FontWeight.Medium)
                            row.student.gender?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Icon(
                            Icons.Filled.CreditCard,
                            contentDescription = null,
                            tint = if (row.localEntity?.rfidCardNumber != null) AccentSuccess else MaterialTheme.colorScheme.outline,
                        )
                        Icon(
                            Icons.Filled.Face,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 8.dp),
                            tint = if (row.hasFaceTemplate) AccentSuccess else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentDetailContent(
    row: AdminDirectoryStudentRow,
    onBack: () -> Unit,
    onRegisterFace: (StudentEntity) -> Unit,
    onAssignCard: (StudentEntity) -> Unit,
) {
    val student = row.student
    val local = row.localEntity

    Column(modifier = Modifier.fillMaxSize().padding(bottom = 24.dp)) {
        BackHeader(student.name, onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(CircleShape),
                tint = MaterialTheme.colorScheme.primary,
            )

            Text("Student Information", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            DetailRow("Student ID", student.id.toString())
            DetailRow("Name", student.name)
            student.gender?.let { DetailRow("Gender", it) }
            student.email?.let { DetailRow("Email", it) }
            student.phone?.let { DetailRow("Phone", it) }
            Text(
                "Editable only from the school's website - this app has no way to change a student's name, gender, or contact details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text("Attendance Identification", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            if (local == null) {
                Text(
                    "Not available yet - this student hasn't come through a synced class roster on this device. " +
                        "Use \"Sync Roster from Server\" on the Admin Dashboard for the class this student is actually " +
                        "scheduled in, then come back here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                DetailRow("RFID card", local.rfidCardNumber ?: "Not assigned")
                OutlinedButton(onClick = { onAssignCard(local) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (local.rfidCardNumber != null) "Re-assign Card" else "Assign Card")
                }

                DetailRow("Face recognition", if (row.hasFaceTemplate) "Enrolled" else "Not enrolled", modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = { onRegisterFace(local) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (row.hasFaceTemplate) "Re-register Face" else "Register Face")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.outline)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
