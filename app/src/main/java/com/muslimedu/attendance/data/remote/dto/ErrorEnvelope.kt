package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/** Parses the body of a non-2xx response (see spec's 401/409/422/500 examples). */
data class ErrorEnvelope(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String?,
    @SerializedName("errors") val errors: Map<String, List<String>>?,
)
