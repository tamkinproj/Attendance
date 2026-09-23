package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small caption-style label above a group of cards/buttons - used to give
 * screens like Settings and Admin Dashboard a consistent grouped-section
 * look instead of each screen hand-rolling its own `Text(..., titleMedium)`.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 8.dp),
    )
}
