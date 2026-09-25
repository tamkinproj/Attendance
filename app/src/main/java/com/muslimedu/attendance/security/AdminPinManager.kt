package com.muslimedu.attendance.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

sealed class PinCheckResult {
    data object Correct : PinCheckResult()
    data class Wrong(val attemptsLeft: Int) : PinCheckResult()
    data class LockedOut(val untilMillis: Long) : PinCheckResult()
}

/**
 * The on-device lock for admin screens (card/face enrollment, adding
 * students, sync). The gate app has no login at startup, so without this
 * anyone holding the device could reassign a student's card. Only a salted
 * PBKDF2 hash is stored, in the same Keystore-backed encrypted prefs file
 * style as [TokenManager].
 *
 * Wrong attempts are counted persistently (so restarting the app doesn't
 * reset them) and lock entry for [LOCKOUT_MILLIS] after [MAX_ATTEMPTS] in a
 * row - a 4-digit PIN is otherwise trivially brute-forced. A forgotten PIN
 * is reset by an admin login instead (see AppRoot), never by clearing data,
 * which would also wipe any gate scans not yet uploaded.
 */
@Singleton
class AdminPinManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun isPinSet(): Boolean = prefs.contains(KEY_HASH)

    /**
     * How many digits the PIN has, so the keypad knows when it's complete and
     * can check it by itself. Null for a PIN set before this was stored - that
     * one is checked with an explicit OK key instead, never tried at each length
     * (every try would count towards the lockout).
     */
    fun pinLength(): Int? = prefs.getInt(KEY_LENGTH, 0).takeIf { it > 0 && isPinSet() }

    suspend fun setPin(pin: String) {
        require(PinHasher.isValidFormat(pin)) { "PIN must be 4-8 digits" }
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash))
            .putInt(KEY_ITERATIONS, PinHasher.ITERATIONS)
            .putInt(KEY_LENGTH, pin.length)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
    }

    suspend fun verify(pin: String, now: Long = System.currentTimeMillis()): PinCheckResult {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        if (lockedUntil > now) return PinCheckResult.LockedOut(lockedUntil)

        val salt = prefs.getString(KEY_SALT, null)?.let(::decode)
        val hash = prefs.getString(KEY_HASH, null)?.let(::decode)
        if (salt == null || hash == null) return PinCheckResult.Wrong(MAX_ATTEMPTS)
        val iterations = prefs.getInt(KEY_ITERATIONS, PinHasher.ITERATIONS)

        val ok = withContext(Dispatchers.Default) { PinHasher.matches(pin, salt, iterations, hash) }
        if (ok) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
            return PinCheckResult.Correct
        }

        val failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        return if (failed >= MAX_ATTEMPTS) {
            val until = now + LOCKOUT_MILLIS
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKED_UNTIL, until).apply()
            PinCheckResult.LockedOut(until)
        } else {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failed).apply()
            PinCheckResult.Wrong(MAX_ATTEMPTS - failed)
        }
    }

    fun clearPin() {
        prefs.edit().clear().apply()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 60_000L

        /** New PINs are 4 digits - the keypad fills four dots and checks the PIN by itself. */
        const val NEW_PIN_LENGTH = 4
        private const val PREFS_FILE_NAME = "secure_admin_pin_prefs"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_LENGTH = "pin_length"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKED_UNTIL = "locked_until"
    }
}
