package com.muslimedu.attendance.data.remote.dto

/**
 * Common response wrapper used by every endpoint on the Laravel backend
 * (see spec's "API Response Format").
 *
 * Built by [com.muslimedu.attendance.data.remote.ApiEnvelopeTypeAdapterFactory],
 * not by Gson's reflective parsing - see that class for the response shapes the
 * backend actually returns.
 */
data class ApiEnvelope<T>(
    val success: Boolean,
    val data: T?,
    val message: String?,
    val errors: Map<String, List<String>>?,
)
