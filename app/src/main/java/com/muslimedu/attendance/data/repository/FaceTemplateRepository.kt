package com.muslimedu.attendance.data.repository

import android.graphics.Bitmap
import androidx.room.withTransaction
import com.muslimedu.attendance.data.db.AppDatabase
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity
import com.muslimedu.attendance.data.local.SettingsRepository
import com.muslimedu.attendance.face.FaceCodec
import com.muslimedu.attendance.face.FaceRecognizer
import com.muslimedu.attendance.face.FaceTemplate
import com.muslimedu.attendance.face.PortableFace
import com.muslimedu.attendance.face.PortableFaceAngle
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.security.EncryptionHelper
import java.util.UUID
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
 * Every method here is local: this class has no
 * [com.muslimedu.attendance.data.remote.ApiService] dependency, only
 * [faceTemplateDao] (Room) and [encryptionHelper] (Android Keystore-wrapped
 * Tink). Sharing a face with the school's other gate phones goes through
 * [com.muslimedu.attendance.sync.FaceSyncManager] (when Face Settings allows
 * it) and the backup file through BackupRepository, both via [exportFace] /
 * [replaceFace] - the only ways a face's numbers leave or enter this class.
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
        // A new registration: queued for the school's other gate phones.
        val version = UUID.randomUUID().toString()
        val rows = templates.mapIndexed { pose, template ->
            FaceTemplateEntity(
                schoolId = schoolId,
                studentId = studentId,
                encryptedEmbedding = encryptionHelper.encrypt(FaceCodec.toBytes(template.embedding)),
                encryptionVersion = template.encryptionVersion,
                enrolledAt = now,
                enrolledBy = enrolledBy,
                livenessScore = template.livenessScore,
                createdAt = now,
                updatedAt = now,
                pose = pose,
                version = version,
                syncStatus = FaceTemplateEntity.SYNC_PENDING,
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

    /**
     * The student's registration in a form that can leave this phone, or
     * null when there's none. The version is the registration's own (a face
     * saved before versions existed gets one from its registration time).
     */
    suspend fun exportFace(schoolId: Int, studentId: Int): PortableFace? {
        val rows = faceTemplateDao.findAllForStudent(schoolId, studentId).ifEmpty { return null }
        return rows.toPortable()
    }

    /** Every registered face of the school, by student id - for the backup file. */
    suspend fun exportAll(schoolId: Int): Map<Int, PortableFace> =
        faceTemplateDao.activeForSchool(schoolId).groupBy { it.studentId }.mapValues { (_, rows) -> rows.sortedBy { it.pose }.toPortable() }

    private fun List<FaceTemplateEntity>.toPortable(): PortableFace {
        val first = first()
        return PortableFace(
            model = first.model,
            version = first.version ?: "local-${first.schoolId}-${first.studentId}-${first.enrolledAt}",
            angles = map { PortableFaceAngle(it.pose, bytesToFloatArray(encryptionHelper.decrypt(it.encryptedEmbedding)), it.livenessScore) },
        )
    }

    /** The registration version on this phone and whether the server has it, or null with no face. */
    suspend fun syncState(schoolId: Int, studentId: Int): Pair<String?, String>? =
        faceTemplateDao.findAllForStudent(schoolId, studentId).firstOrNull()?.let { it.version to it.syncStatus }

    /**
     * Replaces the student's face with [face] from the school server or a
     * backup file - every old row goes, in one transaction. [syncStatus]:
     * synced for one that came from the server, pending for a restored one
     * the server may not have.
     */
    suspend fun replaceFace(schoolId: Int, studentId: Int, face: PortableFace, syncStatus: String, source: String) {
        val now = System.currentTimeMillis()
        val rows = face.angles.sortedBy { it.pose }.mapIndexed { index, angle ->
            FaceTemplateEntity(
                schoolId = schoolId,
                studentId = studentId,
                encryptedEmbedding = encryptionHelper.encrypt(FaceCodec.toBytes(angle.embedding)),
                enrolledAt = now,
                enrolledBy = source,
                livenessScore = angle.livenessScore,
                createdAt = now,
                updatedAt = now,
                model = face.model,
                pose = index,
                version = face.version,
                syncStatus = syncStatus,
            )
        }
        database.withTransaction {
            faceTemplateDao.deleteForStudent(schoolId, studentId)
            faceTemplateDao.insertAll(rows)
        }
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

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray = FaceCodec.fromBytes(bytes)
}
