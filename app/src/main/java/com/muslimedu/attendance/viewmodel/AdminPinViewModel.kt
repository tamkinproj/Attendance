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
    /**
     * Digits in the PIN being entered: the keypad shows that many dots and
     * checks the PIN on the last one. Null for an older PIN of unknown
     * length - the keypad then shows an OK key.
     */
    val pinLength: Int?,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Goes up with every error, so the dots shake and clear even when the message repeats. */
    val errorCount: Int = 0,
    /** Set once the PIN is accepted/created; AppRoot unlocks admin screens and navigates on it. */
    val unlocked: Boolean = false,
)

@HiltViewModel
class AdminPinViewModel @Inject constructor(
    private val pinManager: AdminPinManager,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState(forceCreate = false))
    val state: StateFlow<AdminPinUiState> = _state.asStateFlow()

    private fun initialState(forceCreate: Boolean): AdminPinUiState {
        val creating = forceCreate || !pinManager.isPinSet()
        return AdminPinUiState(
            isCreating = creating,
            pinLength = if (creating) AdminPinManager.NEW_PIN_LENGTH else pinManager.pinLength(),
        )
    }

    /** Called each time the PIN screen opens - the ViewModel outlives the screen (no back stack). */
    fun reset(forceCreate: Boolean = false) {
        _state.value = initialState(forceCreate)
    }

    /** Only after a fresh admin sign-in (see AppRoot) - the recovery path for a forgotten PIN. */
    fun clearPinAfterAdminLogin() {
        pinManager.clearPin()
        _state.value = initialState(forceCreate = true)
    }

    fun consumeUnlocked() {
        _state.value = _state.value.copy(unlocked = false)
    }

    /** The two entries didn't match - shown on the keypad, which starts the new PIN again. */
    fun confirmMismatch() {
        fail("Codes didn't match - enter a new code again")
    }

    fun create(pin: String, confirm: String) {
        when {
            !PinHasher.isValidFormat(pin) -> fail("PIN must be 4 to 8 digits")
            pin != confirm -> confirmMismatch()
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
            when (val result = pinManager.verify(pin)) {
                PinCheckResult.Correct -> _state.value = _state.value.copy(isBusy = false, unlocked = true)
                is PinCheckResult.Wrong -> fail("Wrong code - ${result.attemptsLeft} attempt(s) left")
                is PinCheckResult.LockedOut -> fail("Too many wrong attempts. Try again after ${formatTime(result.untilMillis)}.")
            }
        }
    }

    private fun fail(message: String) {
        val current = _state.value
        _state.value = current.copy(isBusy = false, error = message, errorCount = current.errorCount + 1)
    }

    private fun formatTime(millis: Long): String = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(millis))
}
