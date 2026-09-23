package com.muslimedu.attendance.data.remote.interceptors

import com.muslimedu.attendance.security.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the stored bearer token to every outgoing request, if present, plus
 * a JSON Accept header - without it Laravel renders errors as HTML pages (and
 * redirects unauthenticated requests to a web login route) instead of returning
 * the JSON error envelope this app parses.
 */
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header("Accept", "application/json")
        tokenManager.getToken()?.let { builder.header("Authorization", "Bearer $it") }
        return chain.proceed(builder.build())
    }
}
