package com.muslimedu.attendance.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.muslimedu.attendance.data.remote.dto.UserDto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the bearer token - and the signed-in admin's profile - in an
 * Android Keystore-backed encrypted file (never in plain SharedPreferences).
 *
 * The profile is cached because the app requires a signed-in admin but must
 * still open at a gate with no network: on startup the cached user is shown
 * immediately and `/me` re-validates it in the background, instead of the
 * app being stuck until the server answers.
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveUser(user: UserDto) {
        prefs.edit().putString(KEY_USER, gson.toJson(user)).apply()
    }

    fun getCachedUser(): UserDto? = prefs.getString(KEY_USER, null)?.let {
        try {
            gson.fromJson(it, UserDto::class.java)
        } catch (e: JsonParseException) {
            null
        }
    }

    /** Clears the whole session - token and cached profile together. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "secure_auth_prefs"
        private const val KEY_TOKEN = "bearer_token"
        private const val KEY_USER = "cached_user"
    }
}
