package com.muslimedu.attendance.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 hashing for the device's admin PIN - pure JVM so it's unit-testable without Android. */
object PinHasher {
    const val ITERATIONS = 50_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun isValidFormat(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun matches(pin: String, salt: ByteArray, iterations: Int, expectedHash: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt, iterations), expectedHash)
}
