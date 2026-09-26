package com.muslimedu.attendance.data.repository

import android.graphics.Bitmap
import androidx.room.withTransaction
import com.muslimedu.attendance.data.db.AppDatabase
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

/** One captured angle, checked but not saved yet - [FaceTemplateRepository.saveEnrollment] saves them together. */
sealed class FaceCaptureResult {
    data class Captured(val template: FaceTemplate) : FaceCaptureResult()
    data object NoFaceDetected : FaceCaptureResult()
    data class LivenessTooLow(val score: Float) : FaceCaptureResult()

    /** The face matches one enrolled for another student ([studentId]) - it can't be used. */
    data class AlreadyEnrolled(val studentId: Int, val score: Float) : FaceCaptureResult()
}

/** Scores for every stored angle, as (student id, score) - kept as each student's best. */
internal fun bestPerStudent(scores: List<Pair<Int, Float>>): Map<Int, Float> =
    scores.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.max() }

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
    private val database: AppDatabase,
    private val faceRecognizer: FaceRecognizer,
    private val faceTemplateDao: FaceTemplateDao,
    private val encryptionHelper: EncryptionHelper,
    private val auditLogger: AuditLogger,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun hasTemplate(schoolId: Int, studentId: Int): Boolean =
        faceTemplateDao.findForStudent(schoolId, studentId) != null

    /** (schoolId, studentId) pairs with an active template - for a list badge, not the templates themselves. */
    suspend fun enrolledKeys(): Set<Pair<Int, Int>> = enrolledAngles().keys

    /** How many angles each enrolled student has - one for a face enrolled before angles existed. */
    suspend fun enrolledAngles(): Map<Pair<Int, Int>, Int> =
        faceTemplateDao.angleCounts().associate { (it.schoolId to it.studentId) to it.angles }

    suspend fun angleCount(schoolId: Int, studentId: Int): Int =
        faceTemplateDao.findAllForStudent(schoolId, studentId).size

    /**
     * Turns one captured frame into a template for [studentId], checked but
     * not saved: a face must be found, [checkLiveness] (the straight angle)
     * must clear the liveness threshold, and it must not be another
     * student's face (one face per student).
     */
    suspend fun captureAngle(schoolId: Int, studentId: Int, bitmap: Bitmap, checkLiveness: Boolean): FaceCaptureResult {
        val template = faceRecognizer.enrollFace(bitmap) ?: return FaceCaptureResult.NoFaceDetected
        if (checkLiveness && template.livenessScore < settingsRepository.livenessThreshold.value) {
            return FaceCaptureResult.LivenessTooLow(template.livenessScore)
        }
        duplicateOf(schoolId, studentId, template)?.let { (otherStudentId, score) ->
            auditLogger.log(
                action = AuditLogger.ACTION_FACE_ENROLLED,
                entityType = "student",
                entityId = studentId,
                details = "refused: matches student $otherStudentId (score=$score)",
                success = false,
            )
            return FaceCaptureResult.AlreadyEnrolled(otherStudentId, score)
        }
        return FaceCaptureResult.Captured(template)
    }

    /** 0-1 similarity of two captured angles - whether a later angle is still the same person. */
    fun sameFaceScore(a: FaceTemplate, b: FaceTemplate): Float = faceRecognizer.similarity(a, b)

    /**
     * Saves [templates] (in angle order) as [studentId]'s face, replacing
     * whatever was enrolled before - every angle and any old-model row - in
     * one transaction, so the gate never sees half an enrollment.
     */
    suspend fun saveEnrollment(schoolId: Int, studentId: Int, templates: List<FaceTemplate>, enrolledBy: String?) {
        if (templates.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = templates.mapIndexed { pose, template ->
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
                pose = pose,
            )
        }
        database.withTransaction {
            faceTemplateDao.deleteForStudent(schoolId, studentId)
            faceTemplateDao.insertAll(rows)
        }
        auditLogger.log(
            action = AuditLogger.ACTION_FACE_ENROLLED,
            entityType = "student",
            entityId = studentId,
            details = "angles=${templates.size}, livenessScore=${templates.first().livenessScore}",
        )
    }

    /**
     * One face per student: compares [template] with every angle of every
     * other student's face in the school, each student counted at their
     * best angle. Skipped while the recognizer can't tell people apart (see
     * [FaceRecognizer.canTellPeopleApart]).
     */
    private suspend fun duplicateOf(schoolId: Int, studentId: Int, template: FaceTemplate): Pair<Int, Float>? {
        if (!faceRecognizer.canTellPeopleApart) return null
        val scores = faceTemplateDao.activeForSchool(schoolId)
            .filter { it.studentId != studentId }
            .map { stored -> stored.studentId to faceRecognizer.similarity(template, stored.toTemplate()) }
        return closestOtherStudent(bestPerStudent(scores), studentId, settingsRepository.minMatchScore.value)
    }

    private fun FaceTemplateEntity.toTemplate() = FaceTemplate(
        embedding = bytesToFloatArray(encryptionHelper.decrypt(encryptedEmbedding)),
        livenessScore = livenessScore,
        encryptionVersion = encryptionVersion,
    )

    /** Matches the live face against every enrolled angle of the student; the best score decides. */
    suspend fun verify(schoolId: Int, studentId: Int, liveBitmap: Bitmap): FaceVerificationResult {
        val stored = faceTemplateDao.findAllForStudent(schoolId, studentId)
        if (stored.isEmpty()) return FaceVerificationResult.NoTemplateEnrolled

        val score = faceRecognizer.verifyFace(liveBitmap, stored.map { it.toTemplate() })
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
