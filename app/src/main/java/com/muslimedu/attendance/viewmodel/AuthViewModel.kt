package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.data.repository.AuthRepository
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.security.AuditLogger
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

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.CheckingSession)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    init {
        viewModelScope.launch {
            if (authRepository.hasStoredToken()) {
                // Silent session restore on app startup - not a fresh login, so not audit-logged.
                authRepository.validateSession()
                    .onSuccess { user -> onLoggedIn(user) }
                    .onFailure { _authState.value = AuthState.LoggedOut }
            } else {
                _authState.value = AuthState.LoggedOut
            }
        }
    }

    fun login(email: String, password: String) {
        if (_isLoggingIn.value) return
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            authRepository.login(email, password)
                .onSuccess { user ->
                    onLoggedIn(user)
                    auditLogger.log(AuditLogger.ACTION_LOGIN, entityType = "user", entityId = user.id)
                }
                .onFailure { e -> _loginError.value = e.message ?: "Login failed" }
            _isLoggingIn.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Log before clearing the session - AuditLogger needs a current
            // user to know which school this entry belongs to.
            auditLogger.log(AuditLogger.ACTION_LOGOUT)
            authRepository.logout()
            sessionManager.setUser(null)
            sessionManager.setActiveClass(null)
            _authState.value = AuthState.LoggedOut
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    private fun onLoggedIn(user: UserDto) {
        sessionManager.setUser(user)
        _authState.value = AuthState.LoggedIn(user)
    }
}
