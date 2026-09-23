package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPurple
import com.muslimedu.attendance.viewmodel.AdminPinViewModel

/**
 * Gate for the admin screens. Creates a PIN on first use (or when
 * [forceCreate] - changing it, or after a reset); otherwise asks for it.
 * "Forgot PIN" hands off to an admin login, which is the only way to reset
 * it short of clearing app data (which would also lose unsynced scans).
 */
@Composable
fun AdminPinScreen(
    onUnlocked: () -> Unit,
    onForgotPin: () -> Unit,
    forceCreate: Boolean = false,
    requestId: Long = 0L,
    viewModel: AdminPinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var pin by remember(requestId) { mutableStateOf("") }
    var confirm by remember(requestId) { mutableStateOf("") }

    LaunchedEffect(requestId, forceCreate) { viewModel.reset(forceCreate) }
    LaunchedEffect(state.unlocked) {
        if (state.unlocked) {
            // Consumed immediately: this ViewModel outlives the screen, and a
            // stale `unlocked` would otherwise let the next visit skip the PIN.
            viewModel.consumeUnlocked()
            onUnlocked()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = BrandPurple, modifier = Modifier.size(48.dp))
            Text(
                if (state.isCreating) "Create an admin PIN" else "Enter admin PIN",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                if (state.isCreating) {
                    "4 to 8 digits. Needed to assign cards, add students, enroll faces, and sync. Gate scanning stays open to everyone."
                } else {
                    "Admin screens are locked on this device."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            PinField(value = pin, onValueChange = { pin = it }, label = "PIN")
            if (state.isCreating) {
                PinField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "Confirm PIN",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            state.error?.let { Text(it, color = AccentRed, modifier = Modifier.padding(top = 12.dp)) }

            Button(
                onClick = { if (state.isCreating) viewModel.create(pin, confirm) else viewModel.unlock(pin) },
                enabled = !state.isBusy && pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (state.isCreating) "Save PIN" else "Unlock")
                }
            }

            if (!state.isCreating) {
                TextButton(onClick = onForgotPin, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Forgot PIN? Reset with an admin login")
                }
            }
        }
    }
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.fillMaxWidth(),
    )
}
