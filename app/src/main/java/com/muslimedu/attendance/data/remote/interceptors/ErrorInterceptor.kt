package com.muslimedu.attendance.data.remote.interceptors

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muslimedu.attendance.BuildConfig
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.remote.dto.RefreshTokenData
import com.muslimedu.attendance.security.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject

/**
 * On a 401, attempts exactly one token refresh (via `/refresh-token`,
 * as documented in the spec) and retries the original request with the new
 * token. If refresh also fails, clears the stored token so the app falls
 * back to the login screen on next launch, and returns the original 401 to
 * the caller.
 *
 * Uses a bare, one-off [OkHttpClient] for the refresh call itself rather than
 * the DI-provided client (which includes this same interceptor) to avoid a
 * circular dependency / infinite recursion.
 */
class ErrorInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    // The app-wide Gson, so the refresh response goes through the same
    // envelope parsing as every other endpoint.
    private val gson: Gson,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 401 || request.header(RETRIED_HEADER) != null) {
            return response
        }

        val expiredToken = tokenManager.getToken() ?: return response
        response.close()

        val newToken = attemptRefresh(expiredToken)
        if (newToken == null) {
            tokenManager.clearToken()
            return response
        }
        tokenManager.saveToken(newToken)

        val retried = request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header(RETRIED_HEADER, "1")
            .build()
        return chain.proceed(retried)
    }

    private fun attemptRefresh(expiredToken: String): String? = try {
        val refreshRequest = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}refresh-token")
            .header("Authorization", "Bearer $expiredToken")
            .header("Accept", "application/json")
            .post("".toRequestBody())
            .build()
        REFRESH_CLIENT.newCall(refreshRequest).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val envelopeType = object : TypeToken<ApiEnvelope<RefreshTokenData>>() {}.type
            gson.fromJson<ApiEnvelope<RefreshTokenData>>(body, envelopeType)
                ?.takeIf { it.success }
                ?.data?.token
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val RETRIED_HEADER = "X-Attendance-Retried"
        private val REFRESH_CLIENT = OkHttpClient()
    }
}
