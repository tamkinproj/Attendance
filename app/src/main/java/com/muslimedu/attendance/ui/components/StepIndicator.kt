package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.ui.theme.BrandPrimary

/**
 * A simple "1. Select Student · 2. Tap Card · 3. Confirm"-style progress
 * label atop the RFID/Face enrollment screens' state machines - purely
 * visual, doesn't drive any state itself. [currentStep] is 1-indexed.
 */
@Composable
fun StepIndicator(steps: List<String>, currentStep: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.forEachIndexed { index, label ->
            val stepNumber = index + 1
            val isActive = stepNumber == currentStep
            val isDone = stepNumber < currentStep
            Text(
                text = "$stepNumber. $label",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive || isDone) BrandPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
            if (stepNumber != steps.size) {
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
