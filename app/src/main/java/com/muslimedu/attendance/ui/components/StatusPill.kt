package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Generalizes the colored pill badge pattern first built ad hoc for
 * [com.muslimedu.attendance.ui.screens.admin.GateAttendanceScreen]'s
 * in/out chips and [NetworkStatusBadge] - one reusable component instead of
 * each screen re-implementing its own tinted-`Surface` pill.
 */
@Composable
fun StatusPill(
    label: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = color,
                )
            }
            Text(
                text = label,
                color = color,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
            )
        }
    }
}
