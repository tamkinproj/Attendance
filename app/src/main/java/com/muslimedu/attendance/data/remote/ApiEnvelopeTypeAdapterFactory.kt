package com.muslimedu.attendance.data.remote

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import java.lang.reflect.ParameterizedType

/**
 * Parses every `ApiEnvelope<T>` response.
 *
 * The backend does not use the documented `{success, data, message}` wrapper
 * as consistently as the spec implies: a successful login came back with the
 * payload somewhere other than a `data` object, so reflective parsing produced
 * `success = false` / `data = null` and the app showed the server's own
 * "Login successful" message as a login error.
 *
 * Two tolerances, applied to every endpoint rather than guessed per response:
 * a 2xx body counts as a success unless it explicitly says otherwise (`success`
 * or `status` saying false/error), and the payload is read from `data` when
 * that key is present and from the top-level object when it isn't.
 */
class ApiEnvelopeTypeAdapterFactory : TypeAdapterFactory {

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != ApiEnvelope::class.java) return null
        val payloadType = (type.type as? ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: return null

        val elementAdapter = gson.getAdapter(JsonElement::class.java)
        val payloadAdapter: TypeAdapter<*> = gson.getAdapter(TypeToken.get(payloadType))
        val errorsAdapter = gson.getAdapter(object : TypeToken<Map<String, List<String>>>() {})

        val envelopeAdapter = object : TypeAdapter<ApiEnvelope<Any?>>() {
            override fun write(out: JsonWriter, value: ApiEnvelope<Any?>?) {
                // Envelopes are only ever received, never sent.
                out.nullValue()
            }

            override fun read(reader: JsonReader): ApiEnvelope<Any?>? {
                val root = elementAdapter.read(reader) ?: return null
                if (!root.isJsonObject) return null
                val obj = root.asJsonObject

                val success = readSuccess(obj)
                val payloadElement = when {
                    obj.has(KEY_DATA) && !obj.get(KEY_DATA).isJsonNull -> obj.get(KEY_DATA)
                    success && obj.keySet().any { it !in ENVELOPE_KEYS } -> root
                    else -> null
                }

                return ApiEnvelope(
                    success = success,
                    data = payloadElement?.let { runCatching { payloadAdapter.fromJsonTree(it) }.getOrNull() },
                    message = obj.get(KEY_MESSAGE)?.takeIf { it.isJsonPrimitive }?.asString,
                    errors = obj.get(KEY_ERRORS)?.let { runCatching { errorsAdapter.fromJsonTree(it) }.getOrNull() },
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        return envelopeAdapter as TypeAdapter<T>
    }

    private fun readSuccess(obj: JsonObject): Boolean {
        val raw = obj.get(KEY_SUCCESS)?.takeUnless { it.isJsonNull }
            ?: obj.get(KEY_STATUS)?.takeUnless { it.isJsonNull }
            ?: return true
        if (!raw.isJsonPrimitive) return true
        val primitive = raw.asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        return primitive.asString.lowercase() !in FAILURE_WORDS
    }

    private companion object {
        const val KEY_SUCCESS = "success"
        const val KEY_STATUS = "status"
        const val KEY_DATA = "data"
        const val KEY_MESSAGE = "message"
        const val KEY_ERRORS = "errors"

        val ENVELOPE_KEYS = setOf(KEY_SUCCESS, KEY_STATUS, KEY_DATA, KEY_MESSAGE, KEY_ERRORS, "meta", "code")
        val FAILURE_WORDS = setOf("false", "0", "error", "fail", "failed")
    }
}
