package com.muslimedu.attendance.ui.screens.roster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.data.remote.dto.TeacherClassDto
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.viewmodel.RosterUiState

@Composable
fun RosterGateScreen(
    state: RosterUiState,
    onSelectClass: (TeacherClassDto) -> Unit,
    onRetry: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is RosterUiState.Loading -> LoadingContent()
            is RosterUiState.SelectClass -> ClassPickerContent(state.classes, onSelectClass)
            is RosterUiState.Error -> ErrorContent(state.message, onRetry, onContinueOffline)
            is RosterUiState.Ready -> Unit // caller switches away from this screen
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(text = "Loading your classes...", modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun ClassPickerContent(classes: List<TeacherClassDto>, onSelectClass: (TeacherClassDto) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(text = "Select a Class", style = MaterialTheme.typography.titleLarge)
        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(classes) { teacherClass ->
                Button(
                    onClick = { onSelectClass(teacherClass) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = teacherClass.subjectName?.let { "${teacherClass.sectionName} - $it" }
                            ?: teacherClass.sectionName,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, onContinueOffline: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Couldn't load your class roster", style = MaterialTheme.typography.titleLarge)
        Text(text = message, color = AccentRed, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text("Retry")
        }
        OutlinedButton(onClick = onContinueOffline, modifier = Modifier.padding(top = 8.dp)) {
            Text("Continue Offline")
        }
    }
}
