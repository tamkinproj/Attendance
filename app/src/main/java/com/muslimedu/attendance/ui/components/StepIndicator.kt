package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.ui.theme.BrandPrimary

/**
 * Wizard progress: a numbered circle per step (a tick once done) joined by
 * a line, the label under it. Each step takes an equal share of the width,
 * so five steps still fit a phone. Purely visual. [currentStep] is 1-indexed.
 */
@Composable
fun StepIndicator(steps: List<String>, currentStep: Int, modifier: Modifier = Modifier) {
    val idle = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Step $currentStep of ${steps.size}: ${steps.getOrNull(currentStep - 1).orEmpty()}" },
    ) {
        steps.forEachIndexed { index, label ->
            val number = index + 1
            val done = number < currentStep
            val active = number == currentStep
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Connector(if (index == 0) Color.Transparent else if (number <= currentStep) BrandPrimary else idle, Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(if (done || active) BrandPrimary else MaterialTheme.colorScheme.surface, CircleShape)
                            .border(2.dp, if (done || active) BrandPrimary else idle, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text(
                                "$number",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Connector(if (index == steps.lastIndex) Color.Transparent else if (number < currentStep) BrandPrimary else idle, Modifier.weight(1f))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (done || active) BrandPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Connector(color: Color, modifier: Modifier) {
    Box(modifier = modifier.height(2.dp).background(color))
}
