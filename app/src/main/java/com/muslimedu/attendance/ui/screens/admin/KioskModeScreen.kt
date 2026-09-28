package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.kiosk.KioskController
import com.muslimedu.attendance.ui.theme.AccentSuccess
import com.muslimedu.attendance.viewmodel.KioskModeViewModel

/**
 * A phone or tablet mounted on a kiosk stand for students to scan
 * themselves, with nothing else the app can be pushed to and no way to
 * wander off to the home screen: dark theme, forced landscape, and the
 * device's Home/Recents stop working (Android's own "screen pinning" -
 * see [KioskController]'s doc comment for exactly what that does and
 * doesn't guarantee). Originally tablet-only (a tablet on a stand was all
 * the user first described); the user later asked for a phone kiosk stand
 * too, so this no longer checks device form factor at all - the same
 * dark/landscape/pinned package now runs on either.
 *
 * The way back in is the same admin button/PIN that already sits on the
 * gate dashboard - kiosk mode never blocks that, only leaving the app
 * itself. Turning the switch off here (already past the PIN) exits
 * immediately.
 */
@Composable
fun KioskModeScreen(
    isTabletDevice: Boolean,
    kioskController: KioskController,
    viewModel: KioskModeViewModel = hiltViewModel(),
) {
    val enabled by viewModel.kioskModeEnabled.collectAsState()
    val deviceWord = if (isTabletDevice) "tablet" else "phone"

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        SectionHeader("Kiosk Mode")
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tablet, contentDescription = null, tint = AccentSuccess)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Kiosk Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Dark, landscape, and pinned so students can't leave the app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = viewModel::setKioskModeEnabled)
            }
        }

        SectionHeader("What this does", modifier = Modifier.padding(top = 24.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Bullet("Forces the dark theme and landscape orientation, whatever this $deviceWord's own settings say.")
                Bullet("Pins the app (Android's \"screen pinning\") so the Home button and Recents stop responding.")
                Bullet(
                    "Getting in as admin still works exactly the same way - tap the admin button on the gate " +
                        "screen and enter the PIN. Kiosk mode never blocks that, only leaving the app itself.",
                )
                Bullet(
                    "If you ever need to get out without the PIN, Android's own unpin gesture still works underneath " +
                        "this - hold Back and Recents together (or swipe up and hold, on gesture navigation) - " +
                        "that's a phone-wide feature this app can't disable, so treat it as the last resort, not the " +
                        "normal way out.",
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Try it on this $deviceWord",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    "Screen pinning's first-time prompt (and whether it needs a Settings switch turned on first) " +
                        "varies by device - try it here once before relying on it at the stand. This doesn't touch " +
                        "the switch above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                Row {
                    OutlinedButton(onClick = kioskController::enter, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Pin now")
                    }
                    OutlinedButton(onClick = kioskController::exit, modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Unpin")
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
