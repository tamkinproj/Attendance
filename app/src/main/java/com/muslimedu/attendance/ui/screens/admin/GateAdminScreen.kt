package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.components.InitialsAvatar
import com.muslimedu.attendance.ui.components.QuickActionCard
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentSlate
import com.muslimedu.attendance.ui.theme.BrandPrimary

private data class AdminAction(val icon: ImageVector, val label: String, val color: Color, val onClick: () -> Unit)

/** Admin home for the gate app - only reachable through the device PIN (see AdminPinScreen). */
@Composable
fun GateAdminScreen(
    user: UserDto,
    onStudents: () -> Unit,
    onRegister: () -> Unit,
    onGateSchedule: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onAuditLog: () -> Unit,
    onChangePin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(user.name, BrandPrimary, size = 48.dp)
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "School admin · locks again when you return to the gate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionHeader("Students & cards", modifier = Modifier.padding(top = 24.dp))
        ActionGrid(
            listOf(
                AdminAction(Icons.Filled.HowToReg, "Register Card & Face", AccentGold, onRegister),
                AdminAction(Icons.Filled.People, "Students", BrandPrimary, onStudents),
            ),
        )

        SectionHeader("Device", modifier = Modifier.padding(top = 16.dp))
        ActionGrid(
            listOf(
                AdminAction(Icons.Filled.Schedule, "Gate Schedule", BrandPrimary, onGateSchedule),
                AdminAction(Icons.Filled.CloudSync, "Sync & Account", BrandPrimary, onSync),
                AdminAction(Icons.Filled.Tune, "Face Settings", AccentSlate, onSettings),
                AdminAction(Icons.Filled.History, "Audit Log", AccentGold, onAuditLog),
                AdminAction(Icons.Filled.Lock, "Change PIN", AccentRed, onChangePin),
            ),
        )
    }
}

/** Fixed 2-column grid (not a wrapping FlowRow) so this stays clear of experimental layout APIs. */
@Composable
private fun ActionGrid(actions: List<AdminAction>) {
    actions.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { action ->
                QuickActionCard(
                    icon = action.icon,
                    label = action.label,
                    color = action.color,
                    onClick = action.onClick,
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
        }
    }
}
