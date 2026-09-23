package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentTeal

/**
 * Purely informational, shared by [com.muslimedu.attendance.ui.screens.scanner.RfidScanScreen]
 * and [com.muslimedu.attendance.ui.screens.admin.AdminDashboardScreen] - see
 * [com.muslimedu.attendance.util.NetworkMonitor]'s doc comment for why
 * nothing in this app actually gates on connectivity: a scan is recorded
 * locally and queued for sync either way, online or off. This only sets
 * expectations for whether that's likely to happen right away.
 */
@Composable
fun NetworkStatusBadge(isOnline: Boolean, modifier: Modifier = Modifier) {
    val label = if (isOnline) "Online - syncing" else "Offline - scans will sync later"
    val color = if (isOnline) AccentTeal else AccentGold

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
                imageVector = if (isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
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
