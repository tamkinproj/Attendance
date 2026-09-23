package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A single colored stat tile (value + label) - originally built inline in
 * AdminDashboardScreen for its student/sync counts, pulled out here so
 * TeacherDashboardScreen's Present/Absent/Late summary can reuse the exact
 * same look instead of duplicating it.
 */
@Composable
fun StatChip(value: Int, label: String, color: Color, container: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(84.dp),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
