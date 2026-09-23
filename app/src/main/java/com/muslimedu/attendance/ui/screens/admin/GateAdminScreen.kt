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
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.ui.components.QuickActionCard
import com.muslimedu.attendance.ui.theme.AccentGold
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.AccentTeal
import com.muslimedu.attendance.ui.theme.BrandPurple

private data class AdminAction(val icon: ImageVector, val label: String, val color: Color, val onClick: () -> Unit)

/** Admin home for the gate-only app - only reachable through the device PIN (see AdminPinScreen). */
@Composable
fun GateAdminScreen(
    onStudents: () -> Unit,
    onAssignCard: () -> Unit,
    onEnrollFace: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onAuditLog: () -> Unit,
    onChangePin: () -> Unit,
) {
    val actions = listOf(
        AdminAction(Icons.Filled.People, "Students", BrandPurple, onStudents),
        AdminAction(Icons.Filled.CreditCard, "Assign Card", AccentGold, onAssignCard),
        AdminAction(Icons.Filled.Face, "Enroll Face", AccentTeal, onEnrollFace),
        AdminAction(Icons.Filled.CloudSync, "Sync & Account", BrandPurple, onSync),
        AdminAction(Icons.Filled.Settings, "Face Settings", AccentTeal, onSettings),
        AdminAction(Icons.Filled.History, "Audit Log", AccentRed, onAuditLog),
        AdminAction(Icons.Filled.Lock, "Change PIN", AccentGold, onChangePin),
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text(
                "Admin screens lock again when you go back to the gate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
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
    }
}
