package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSuccess

/**
 * Shows whether a reader is physically plugged in, naming the device so it's
 * clear the app found the actual reader and not some other USB accessory.
 * Originally built inline in [com.muslimedu.attendance.ui.screens.scanner.RfidScanScreen] -
 * pulled out here so [com.muslimedu.attendance.ui.screens.dashboard.TeacherDashboardScreen]'s
 * reader-status card can show the exact same live badge instead of a second
 * copy that could drift out of sync visually.
 */
@Composable
fun ReaderStatusBadge(status: ReaderStatus, modifier: Modifier = Modifier) {
    val label = when {
        status.connected -> status.deviceName?.let { "Reader connected - $it" } ?: "Reader connected"
        else -> "No reader detected - plug in a USB reader"
    }
    val color = when {
        status.connected -> AccentSuccess
        else -> AccentRed
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (status.connected) Icons.Filled.Usb else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = color,
            )
            Text(
                text = label,
                color = color,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
