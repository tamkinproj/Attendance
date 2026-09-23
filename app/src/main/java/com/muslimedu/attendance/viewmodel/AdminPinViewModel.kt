package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.security.AdminPinManager
import com.muslimedu.attendance.security.PinCheckResult
import com.muslimedu.attendance.security.PinHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class AdminPinUiState(
    /** True when no PIN exists yet (first use, or after a reset) - the screen asks to create one. */
    val isCreating: Boolean,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Set once the PIN is accepted/created; AppRoot unlocks admin screens and navigates on it. */
    val unlocked: Boolean = false,
)

@HiltViewModel
class AdminPinViewModel @Inject constructor(
    private val pinManager: AdminPinManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminPinUiState(isCreating = !pinManager.isPinSet()))
    val state: StateFlow<AdminPinUiState> = _state.asStateFlow()

    /** Called each time the PIN screen opens - the ViewModel outlives the screen (no back stack). */
    fun reset(forceCreate: Boolean = false) {
        _state.value = AdminPinUiState(isCreating = forceCreate || !pinManager.isPinSet())
    }

    /** Only after a fresh admin sign-in (see AppRoot) - the recovery path for a forgotten PIN. */
    fun clearPinAfterAdminLogin() {
        pinManager.clearPin()
        _state.value = AdminPinUiState(isCreating = true)
    }

    fun consumeUnlocked() {
        _state.value = _state.value.copy(unlocked = false)
    }

    fun create(pin: String, confirm: String) {
        when {
            !PinHasher.isValidFormat(pin) -> _state.value = _state.value.copy(error = "PIN must be 4 to 8 digits")
            pin != confirm -> _state.value = _state.value.copy(error = "PINs don't match")
            else -> viewModelScope.launch {
                _state.value = _state.value.copy(isBusy = true, error = null)
                pinManager.setPin(pin)
                _state.value = _state.value.copy(isBusy = false, unlocked = true)
            }
        }
    }

    fun unlock(pin: String) {
        if (pin.isBlank() || _state.value.isBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null)
            _state.value = when (val result = pinManager.verify(pin)) {
                PinCheckResult.Correct -> _state.value.copy(isBusy = false, unlocked = true)
                is PinCheckResult.Wrong -> _state.value.copy(
                    isBusy = false,
                    error = "Wrong PIN - ${result.attemptsLeft} attempt(s) left",
                )
                is PinCheckResult.LockedOut -> _state.value.copy(
                    isBusy = false,
                    error = "Too many wrong attempts. Try again after ${formatTime(result.untilMillis)}.",
                )
            }
        }
    }

    private fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
