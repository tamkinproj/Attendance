package com.muslimedu.attendance.ui.screens.dashboard

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.components.EmptyState
import com.muslimedu.attendance.ui.components.StatusPill
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.BrandPurple
import com.muslimedu.attendance.viewmodel.ClassRosterViewModel
import com.muslimedu.attendance.viewmodel.RosterRow

/**
 * Today's roster for the active class, with each student's scan status so
 * far today. Read-only for a teacher; an admin additionally sees a "Mark
 * Present" action on a not-yet-scanned row (manual override - see
 * [ClassRosterViewModel.markPresentManually]).
 */
@Composable
fun ClassRosterScreen(isAdmin: Boolean = false, viewModel: ClassRosterViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()
    val sectionName by viewModel.sectionName.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows else rows.filter { it.student.name.contains(query, ignoreCase = true) || it.student.code.contains(query, ignoreCase = true) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = sectionName ?: "Class Roster",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search students...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (rows.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Groups,
                    title = "No active class roster",
                    message = "Select a class from My Classes, or sync a roster from the Admin Dashboard.",
                    modifier = Modifier.padding(top = 32.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    items(filtered, key = { it.student.id }) { row ->
                        RosterRowCard(
                            row,
                            loadPhoto = { viewModel.loadPhoto(row.student) },
                            isAdmin = isAdmin,
                            onMarkPresent = { viewModel.markPresentManually(row.student) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterRowCard(
    row: RosterRow,
    loadPhoto: suspend () -> Bitmap?,
    isAdmin: Boolean,
    onMarkPresent: () -> Unit,
) {
    val photo by produceState<Bitmap?>(initialValue = null, row.student.id) { value = loadPhoto() }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                Text(row.student.name, fontWeight = FontWeight.Medium)
                Text(row.student.code, style = MaterialTheme.typography.bodySmall)
            }
            if (row.checkInTime != null) {
                StatusPill(label = "Present · ${row.checkInTime}", color = AccentTeal)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    StatusPill(label = "Not scanned", color = AccentGold)
                    if (isAdmin) {
                        Text(
                            text = "Mark Present",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandPurple,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable(onClick = onMarkPresent),
                        )
                    }
                }
            }
        }
    }
}
