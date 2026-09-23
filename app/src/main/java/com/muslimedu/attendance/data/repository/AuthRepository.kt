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
 * The app is gate-only and works offline without any login; signing in is
 * only needed to upload gate scans and download the student list. Both of
 * those backend endpoints gate on `requireAdmin()`, which checks
 * `role_id === 2` specifically - a teacher or even a superadmin gets a 403
 * from them (confirmed from `ApiController::requireAdmin()`), so letting
 * those roles sign in here would only produce an account that can't sync.
 */
private val ALLOWED_APP_ROLES = setOf("admin")
private const val WRONG_ROLE_MESSAGE = "Only school admin accounts can sync gate attendance"

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val deviceBinding: DeviceBindingRepository,
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
                Result.failure(Exception(WRONG_ROLE_MESSAGE))
            !deviceBinding.canUse(user.schoolId) ->
                // Checked before the token is saved, same as the role check.
                Result.failure(Exception(deviceBinding.mismatchMessage()))
            else -> {
                tokenManager.saveToken(token)
                deviceBinding.bindTo(user.schoolId)
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
                    Result.failure(Exception(WRONG_ROLE_MESSAGE))
                }
                !deviceBinding.canUse(user.schoolId) -> {
                    tokenManager.clearToken()
                    Result.failure(Exception(deviceBinding.mismatchMessage()))
                }
                else -> {
                    // Covers an install from before offline mode existed: a
                    // still-valid admin session links the device on startup.
                    deviceBinding.bindTo(user.schoolId)
                    Result.success(user)
                }
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
