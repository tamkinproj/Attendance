package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.components.SectionHeader
import com.muslimedu.attendance.ui.theme.AccentSuccess
import com.muslimedu.attendance.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val minMatchScore by viewModel.minMatchScore.collectAsState()
    val livenessThreshold by viewModel.livenessThreshold.collectAsState()
    val shareFaces by viewModel.shareFaces.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            SectionHeader("Face Verification")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Face, contentDescription = null, tint = AccentSuccess)
                        Text(
                            "Match threshold",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    Text(
                        text = "Minimum match score: %.2f".format(minMatchScore),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "How closely a live scan must match the enrolled template to be accepted as a positive identification. Higher is stricter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = minMatchScore,
                        onValueChange = viewModel::setMinMatchScore,
                        valueRange = 0.5f..1.0f,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )

                    Text(
                        text = "Liveness threshold: %.2f".format(livenessThreshold),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "Minimum liveness score required to accept a face enrollment capture. Higher is stricter, but more likely to reject valid captures in poor lighting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = livenessThreshold,
                        onValueChange = viewModel::setLivenessThreshold,
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            SectionHeader("Sharing", modifier = Modifier.padding(top = 24.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = AccentSuccess)
                        Text(
                            "Share faces with the school's other gate phones",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                        Switch(checked = shareFaces, onCheckedChange = viewModel::setShareFaces)
                    }
                    Text(
                        "A face registered on any gate phone works on all of them, and a replacement phone gets every face " +
                            "back when it syncs. Faces are sent to the school server as numbers, not photos, stored " +
                            "encrypted, and only given to the school's admins. Faces are personal data - parents should " +
                            "know and agree. Off: faces stay on this phone only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            OutlinedButton(
                onClick = viewModel::resetToDefaults,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Reset to Defaults")
            }
        }
    }
}
