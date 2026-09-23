package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.LoginRequest
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.security.TokenManager
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This app is built for teachers taking attendance and admins managing the
 * school's roster/sync - not for the backend's many other login-eligible
 * roles (`student`, `parent`, `accountant`, `librarian`, ... - see
 * `ApiController::login()`'s `$allowedRoles` map, which decides who can even
 * authenticate at the platform level and does *not* itself restrict which of
 * those roles belong in *this* app). Without a check here, any of those
 * other roles could log in with valid credentials and land on the RFID scan
 * screen with no real permission model behind them. `superadmin` is allowed
 * alongside `admin` for consistency with [com.muslimedu.attendance.ui.navigation.AppRoot]'s
 * own `ADMIN_ROLES`, which already treats the two the same for who sees the
 * Admin Dashboard - even though, confirmed from the real backend, a
 * superadmin will still get a 403 from the admin-only endpoints that check
 * `role_id === 2` specifically (see `AdminDirectoryRepository`'s doc
 * comment).
 */
private val ALLOWED_APP_ROLES = setOf("teacher", "admin", "superadmin")

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
) {
    fun hasStoredToken(): Boolean = tokenManager.getToken() != null

    suspend fun login(email: String, password: String): Result<UserDto> = try {
        val response = apiService.login(LoginRequest(email, password))
        val data = response.data
        val token = data?.token
        val user = data?.user
        when {
            !response.success ->
                Result.failure(Exception(response.message ?: "Login failed"))
            data?.requiresTwoFactor == true ->
                Result.failure(Exception("This account has two-factor authentication enabled, which this app doesn't support yet"))
            token == null ->
                Result.failure(Exception("The server accepted the login but returned no token"))
            user == null ->
                Result.failure(Exception("The server accepted the login but returned no user profile"))
            user.role !in ALLOWED_APP_ROLES ->
                // Never saved - a role this app doesn't support gets no token
                // written at all, not one saved-then-discarded.
                Result.failure(Exception("This app is for teachers and admins only"))
            else -> {
                tokenManager.saveToken(token)
                Result.success(user)
            }
        }
    } catch (e: HttpException) {
        Result.failure(Exception(e.extractApiErrorMessage() ?: "Login failed"))
    } catch (e: IOException) {
        Result.failure(Exception("Network error - check your connection"))
    }

    /**
     * Re-validates a stored token against `/me`. Clears it if the server
     * rejects it, or if the account's role isn't one this app allows -
     * covers a token saved by an older build before this restriction
     * existed, or a role change on the backend since the last login.
     */
    suspend fun validateSession(): Result<UserDto> {
        if (!hasStoredToken()) return Result.failure(Exception("Not logged in"))
        return try {
            val response = apiService.me()
            val user = response.data?.user
            when {
                !response.success || user == null -> {
                    tokenManager.clearToken()
                    Result.failure(Exception(response.message ?: "Session expired"))
                }
                user.role !in ALLOWED_APP_ROLES -> {
                    tokenManager.clearToken()
                    Result.failure(Exception("This app is for teachers and admins only"))
                }
                else -> Result.success(user)
            }
        } catch (e: HttpException) {
            if (e.code() == 401) tokenManager.clearToken()
            Result.failure(Exception(e.extractApiErrorMessage() ?: "Session expired"))
        } catch (e: IOException) {
            // Could just be offline - don't force a re-login for a network blip.
            Result.failure(Exception("Network error - check your connection"))
        }
    }

    suspend fun logout() {
        try {
            apiService.logout()
        } catch (e: Exception) {
            // Best-effort server-side revocation; the local token is cleared regardless.
        }
        tokenManager.clearToken()
    }
}
