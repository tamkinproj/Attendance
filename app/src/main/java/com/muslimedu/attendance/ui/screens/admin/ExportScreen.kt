package com.muslimedu.attendance.ui.screens.admin

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.viewmodel.ExportState
import com.muslimedu.attendance.viewmodel.ExportViewModel

@Composable
fun ExportScreen(viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state) {
        val current = state
        if (current is ExportState.Success) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, current.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share attendance export"))
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Export Attendance", style = MaterialTheme.typography.titleLarge)
            Text(
                "Exports every locally recorded attendance row (synced, pending, and failed) as a CSV file.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            when (val current = state) {
                is ExportState.Idle -> Button(onClick = viewModel::export, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Export to CSV")
                }
                is ExportState.Exporting -> CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                is ExportState.Success -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Exported ${current.rowCount} rows", modifier = Modifier.padding(top = 24.dp))
                    Button(onClick = viewModel::reset, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Export Again")
                    }
                }
                is ExportState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(current.reason, modifier = Modifier.padding(top = 24.dp))
                    Button(onClick = viewModel::reset, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}
