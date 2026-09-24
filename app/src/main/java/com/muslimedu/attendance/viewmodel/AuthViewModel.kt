package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.data.repository.AuthRepository
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.sync.GateSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState {
    data object CheckingSession : AuthState()
    data object LoggedOut : AuthState()
    data class LoggedIn(val user: UserDto) : AuthState()
}

/**
 * The app requires a signed-in school admin. A session survives restarts
 * offline: the cached profile is shown straight away and `/me` re-checks it
 * in the background - only an explicit rejection (401, wrong role, other
 * school) signs the device out, never a missing network.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val auditLogger: AuditLogger,
    private val gateSyncManager: GateSyncManager,
    private val deviceSettings: DeviceSettings,
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.CheckingSession)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    /** True between a fresh sign-in and the end of the sync step that follows it. */
    val postLoginSyncPending: StateFlow<Boolean> = deviceSettings.postLoginSyncPending

    /**
     * When the last explicit, password-entered sign-in succeeded - not a
     * restored session. Resetting a forgotten admin PIN keys off this, so an
     * admin session left open on the device isn't enough to reset it.
     */
    private val _lastLoginAt = MutableStateFlow<Long?>(null)
    val lastLoginAt: StateFlow<Long?> = _lastLoginAt.asStateFlow()

    init {
        viewModelScope.launch { restoreSession() }
    }

    private suspend fun restoreSession() {
        if (!authRepository.hasStoredToken()) {
            _authState.value = AuthState.LoggedOut
            return
        }
        authRepository.cachedUser()?.let(::onLoggedIn)
        // Not a fresh login, so not audit-logged.
        authRepository.validateSession()
            .onSuccess(::onLoggedIn)
            .onFailure { e ->
                when {
                    // Rejected by the server (validateSession already cleared the token).
                    !authRepository.hasStoredToken() -> signedOut(e.message)
                    // Offline with nothing cached (an install from before the cache existed).
                    _authState.value !is AuthState.LoggedIn ->
                        signedOut("Can't reach the server to restore your session - check the connection and sign in")
                    // Offline with a cached profile: keep working.
                    else -> Unit
                }
            }
    }

    /**
     * [syncAfter] is false only for the "forgot PIN" re-authentication inside
     * the app, which shouldn't interrupt the admin with the sync step.
     */
    fun login(email: String, password: String, syncAfter: Boolean = true) {
        if (_isLoggingIn.value) return
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            authRepository.login(email, password)
                .onSuccess { user ->
                    // Set before the auth state flips, so AppRoot goes straight
                    // to the sync step instead of flashing the gate first.
                    if (syncAfter) deviceSettings.setPostLoginSyncPending(true)
                    onLoggedIn(user)
                    _lastLoginAt.value = System.currentTimeMillis()
                    auditLogger.log(AuditLogger.ACTION_LOGIN, entityType = "user", entityId = user.id)
                    if (!syncAfter) launch { gateSyncManager.flush() }
                }
                .onFailure { e -> _loginError.value = e.message ?: "Sign in failed" }
            _isLoggingIn.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Log before clearing the session - AuditLogger needs a current
            // user to know which school this entry belongs to.
            auditLogger.log(AuditLogger.ACTION_LOGOUT)
            authRepository.logout()
            deviceSettings.setPostLoginSyncPending(false)
            signedOut(null)
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    private fun onLoggedIn(user: UserDto) {
        sessionManager.setUser(user)
        _authState.value = AuthState.LoggedIn(user)
    }

    private fun signedOut(reason: String?) {
        sessionManager.setUser(null)
        sessionManager.setActiveClass(null)
        _loginError.value = reason
        _authState.value = AuthState.LoggedOut
    }
}
