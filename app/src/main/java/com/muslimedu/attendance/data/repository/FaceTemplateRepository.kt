package com.muslimedu.attendance.data.repository

import android.graphics.Bitmap
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity
import com.muslimedu.attendance.data.local.SettingsRepository
import com.muslimedu.attendance.face.FaceRecognizer
import com.muslimedu.attendance.face.FaceTemplate
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.security.EncryptionHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

sealed class FaceVerificationResult {
    data class Matched(val score: Float) : FaceVerificationResult()
    data class NotMatched(val score: Float) : FaceVerificationResult()
    data object NoFaceDetected : FaceVerificationResult()
    data object NoTemplateEnrolled : FaceVerificationResult()
}

sealed class FaceEnrollResult {
    data class Success(val livenessScore: Float) : FaceEnrollResult()
    data object NoFaceDetected : FaceEnrollResult()
    data class LivenessTooLow(val score: Float) : FaceEnrollResult()

    /** The face matches the one enrolled for another student ([studentId]) - nothing was saved. */
    data class AlreadyEnrolled(val studentId: Int, val score: Float) : FaceEnrollResult()
}

/**
 * The other student whose face best matches, if that match reaches
 * [threshold] - the gate's own match threshold, so a face is a duplicate
 * exactly when the gate would accept it as that other student. [scores] is
 * student id -> similarity; [ownStudentId] (a re-enrollment) never counts.
 */
internal fun closestOtherStudent(scores: Map<Int, Float>, ownStudentId: Int, threshold: Float): Pair<Int, Float>? =
    scores.filterKeys { it != ownStudentId }
        .maxByOrNull { it.value }
        ?.takeIf { it.value >= threshold }
        ?.toPair()

/**
 * Match/liveness thresholds are admin-adjustable via [SettingsRepository]
 * (see the Settings screen), defaulting to the spec's `face_verification`
 * settings block values.
 *
 * Every method here is local-only, by construction rather than convention:
 * this class has no [com.muslimedu.attendance.data.remote.ApiService]
 * dependency at all, only [faceTemplateDao] (Room) and [encryptionHelper]
 * (Android Keystore-wrapped Tink). A face is never uploaded, and no endpoint
 * in the spec even accepts one - enrolling on one device does not make a
 * student recognizable on another.
 */
@Singleton
class FaceTemplateRepository @Inject constructor(
    private val faceRecognizer: FaceRecognizer,
    private val faceTemplateDao: FaceTemplateDao,
    private val encryptionHelper: EncryptionHelper,
    private val auditLogger: AuditLogger,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun hasTemplate(schoolId: Int, studentId: Int): Boolean =
        faceTemplateDao.findForStudent(schoolId, studentId) != null

    /** (schoolId, studentId) pairs with an active template - for a list badge, not the templates themselves. */
    suspend fun enrolledKeys(): Set<Pair<Int, Int>> =
        faceTemplateDao.activeKeys().mapTo(mutableSetOf()) { it.schoolId to it.studentId }

    suspend fun enroll(schoolId: Int, studentId: Int, bitmap: Bitmap, enrolledBy: String?): FaceEnrollResult {
        val template = faceRecognizer.enrollFace(bitmap) ?: return FaceEnrollResult.NoFaceDetected
        if (template.livenessScore < settingsRepository.livenessThreshold.value) {
            return FaceEnrollResult.LivenessTooLow(template.livenessScore)
        }
        duplicateOf(schoolId, studentId, template)?.let { (otherStudentId, score) ->
            auditLogger.log(
                action = AuditLogger.ACTION_FACE_ENROLLED,
                entityType = "student",
                entityId = studentId,
                details = "refused: matches student $otherStudentId (score=$score)",
                success = false,
            )
            return FaceEnrollResult.AlreadyEnrolled(otherStudentId, score)
        }
        val now = System.currentTimeMillis()
        faceTemplateDao.upsert(
            FaceTemplateEntity(
                schoolId = schoolId,
                studentId = studentId,
                encryptedEmbedding = encryptionHelper.encrypt(floatArrayToBytes(template.embedding)),
                encryptionVersion = template.encryptionVersion,
                enrolledAt = now,
                enrolledBy = enrolledBy,
                livenessScore = template.livenessScore,
                createdAt = now,
                updatedAt = now,
            ),
        )
        auditLogger.log(
            action = AuditLogger.ACTION_FACE_ENROLLED,
            entityType = "student",
            entityId = studentId,
            details = "livenessScore=${template.livenessScore}",
        )
        return FaceEnrollResult.Success(template.livenessScore)
    }

    /**
     * One face per student: compares [template] with every other student's
     * enrolled face in the school. Skipped while the recognizer can't tell
     * people apart (see [FaceRecognizer.canTellPeopleApart]).
     */
    private suspend fun duplicateOf(schoolId: Int, studentId: Int, template: FaceTemplate): Pair<Int, Float>? {
        if (!faceRecognizer.canTellPeopleApart) return null
        val scores = faceTemplateDao.activeForSchool(schoolId)
            .filter { it.studentId != studentId }
            .associate { stored -> stored.studentId to faceRecognizer.similarity(template, stored.toTemplate()) }
        return closestOtherStudent(scores, studentId, settingsRepository.minMatchScore.value)
    }

    private fun FaceTemplateEntity.toTemplate() = FaceTemplate(
        embedding = bytesToFloatArray(encryptionHelper.decrypt(encryptedEmbedding)),
        livenessScore = livenessScore,
        encryptionVersion = encryptionVersion,
    )

    suspend fun verify(schoolId: Int, studentId: Int, liveBitmap: Bitmap): FaceVerificationResult {
        val stored = faceTemplateDao.findForStudent(schoolId, studentId)
            ?: return FaceVerificationResult.NoTemplateEnrolled

        val score = faceRecognizer.verifyFace(liveBitmap, stored.toTemplate())
            ?: return FaceVerificationResult.NoFaceDetected

        return if (score >= settingsRepository.minMatchScore.value) {
            FaceVerificationResult.Matched(score)
        } else {
            FaceVerificationResult.NotMatched(score)
        }
    }

    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / Float.SIZE_BYTES)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat(i * Float.SIZE_BYTES)
        }
        return floats
    }
}
