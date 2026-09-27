package com.muslimedu.attendance.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file an admin exports from one gate phone and imports on
 * another (a lost or broken phone, a new gate): every student's card,
 * parent number and registered face. Faces are biometric data and the file
 * can be copied anywhere, so it is always encrypted with a password the
 * admin chooses: AES-256-GCM, the key derived with PBKDF2-HMAC-SHA256.
 *
 * File layout: "GATEBAK1" | iterations (int) | salt (16) | iv (12) | ciphertext+tag.
 */
object GateBackupCodec {
    const val FORMAT = "gate-attendance-backup"
    const val VERSION = 1
    const val MIN_PASSWORD_LENGTH = 6
    const val FILE_EXTENSION = "gatebak"
    private val MAGIC = "GATEBAK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val gson = Gson()

    class BackupException(message: String) : Exception(message)

    fun encode(backup: GateBackup, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray =
        encrypt(gson.toJson(backup).toByteArray(Charsets.UTF_8), password, random)

    /** The backup in [file], checked; [BackupException] with a readable reason when it can't be used. */
    fun decode(file: ByteArray, password: CharArray): GateBackup {
        val json = String(decrypt(file, password), Charsets.UTF_8)
        val backup = runCatching { gson.fromJson(json, GateBackup::class.java) }.getOrNull()
        @Suppress("SENSELESS_COMPARISON") // Gson leaves missing fields null whatever Kotlin says
        if (backup == null || backup.format != FORMAT || backup.students == null) throw BackupException("This isn't a Gate Attendance backup file.")
        if (backup.version > VERSION) throw BackupException("This backup was made by a newer app - update the app first.")
        return backup
    }

    fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + IV_BYTES + sealed.size)
            .put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).put(sealed).array()
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        if (file.size < MAGIC.size + 4 + SALT_BYTES + IV_BYTES + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This isn't a Gate Attendance backup file.")
        }
        val buffer = ByteBuffer.wrap(file, MAGIC.size, file.size - MAGIC.size)
        val iterations = buffer.int
        if (iterations !in 10_000..10_000_000) throw BackupException("This backup file is damaged.")
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val iv = ByteArray(IV_BYTES).also { buffer.get(it) }
        val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC)
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            throw BackupException("Wrong password, or the file is damaged.")
        } catch (e: GeneralSecurityException) {
            throw BackupException("Wrong password, or the file is damaged.")
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/** What a backup file holds (JSON inside the encryption). */
data class GateBackup(
    @SerializedName("format") val format: String = GateBackupCodec.FORMAT,
    @SerializedName("version") val version: Int = GateBackupCodec.VERSION,
    /** The school the phone was linked to - a backup only restores into the same school. */
    @SerializedName("school_id") val schoolId: Int,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("students") val students: List<BackupStudent>,
)

data class BackupStudent(
    @SerializedName("code") val code: String,
    @SerializedName("student_id") val studentId: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("rfid_uid") val rfidUid: String?,
    @SerializedName("parent_phone") val parentPhone: String?,
    @SerializedName("face") val face: BackupFace?,
)

data class BackupFace(
    @SerializedName("model") val model: String,
    @SerializedName("version") val version: String,
    @SerializedName("angles") val angles: List<BackupFaceAngle>,
)

data class BackupFaceAngle(
    @SerializedName("pose") val pose: Int,
    /** base64 of little-endian float32 (FaceCodec). */
    @SerializedName("embedding") val embedding: String,
    @SerializedName("liveness_score") val livenessScore: Float,
)
