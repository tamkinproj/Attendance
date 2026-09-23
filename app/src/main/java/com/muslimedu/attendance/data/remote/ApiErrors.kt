package com.muslimedu.attendance.data.remote

import com.google.gson.Gson
import com.muslimedu.attendance.data.remote.dto.ErrorEnvelope
import retrofit2.HttpException

private val errorGson = Gson()

/** Pulls a human-readable message out of a non-2xx response body (see spec's 401/409/422/500 examples). */
fun HttpException.extractApiErrorMessage(): String? = try {
    val body = response()?.errorBody()?.string()
    val envelope = errorGson.fromJson(body, ErrorEnvelope::class.java)
    envelope?.message ?: envelope?.errors?.values?.flatten()?.firstOrNull()
} catch (e: Exception) {
    null
}
