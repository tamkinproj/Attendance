package com.muslimedu.attendance.face

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Real ML Kit face *detection* wired up to a geometric-landmark *placeholder*
 * standing in for real face *recognition* (see [FaceRecognizer]'s doc comment
 * for why those are different things and why this project doesn't have the
 * latter). [extractTemplate]'s "embedding" is a small vector of normalized
 * distances between a handful of facial landmarks - it is not a biometric
 * embedding, is not rotation-invariant, is sensitive to expression, and is
 * trivially spoofable. It exists so the enrollment/verification/encryption/
 * storage pipeline has something real to run end to end. Swap it for a
 * TensorFlow Lite embedding model (e.g. MobileFaceNet) behind this same
 * [FaceRecognizer] interface before relying on this for actual anti-fraud.
 */
@Singleton
class MlKitFaceRecognizer @Inject constructor() : FaceRecognizer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build(),
    )

    override suspend fun captureFace(bitmap: Bitmap): FaceData? {
        val face = detectFirstFace(bitmap) ?: return null
        return toFaceData(bitmap, face)
    }

    override suspend fun extractTemplate(faceData: FaceData): FaceTemplate =
        FaceTemplate(embedding = geometricEmbedding(faceData.landmarks), livenessScore = faceData.livenessScore)

    override suspend fun verifyFace(liveFrame: Bitmap, storedTemplate: FaceTemplate): Float? {
        val face = detectFirstFace(liveFrame) ?: return null
        val liveData = toFaceData(liveFrame, face)
        val liveEmbedding = geometricEmbedding(liveData.landmarks)
        return cosineSimilarity(liveEmbedding, storedTemplate.embedding).coerceIn(0f, 1f)
    }

    override suspend fun enrollFace(bitmap: Bitmap): FaceTemplate? {
        val data = captureFace(bitmap) ?: return null
        return extractTemplate(data)
    }

    private suspend fun detectFirstFace(bitmap: Bitmap): Face? = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces -> continuation.resume(faces.firstOrNull()) }
            .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }

    private fun toFaceData(bitmap: Bitmap, face: Face): FaceData = FaceData(
        bitmap = bitmap,
        landmarks = LANDMARK_TYPES.mapNotNull { type -> face.getLandmark(type)?.position },
        leftEyeOpenProbability = face.leftEyeOpenProbability,
        rightEyeOpenProbability = face.rightEyeOpenProbability,
        headEulerAngleY = face.headEulerAngleY,
        livenessScore = LivenessDetector.score(face),
    )

    /**
     * Ratios of inter-landmark distances, normalized by eye-to-eye distance
     * for scale invariance. Note this indexes by *detection order*, not
     * landmark type - if ML Kit detects a different subset of landmarks
     * between enrollment and a later verification attempt (common; not
     * every landmark is reliably detected at every head angle), the same
     * array index can represent a different landmark pair each time. One
     * more reason this is a placeholder, not the swap-in-a-real-model target.
     */
    private fun geometricEmbedding(landmarks: List<PointF>): FloatArray {
        if (landmarks.size < 2) return FloatArray(EMBEDDING_SIZE)
        val base = distance(landmarks[0], landmarks.getOrElse(1) { landmarks[0] }).takeIf { it > 0f } ?: 1f
        val embedding = FloatArray(EMBEDDING_SIZE)
        var index = 0
        for (i in landmarks.indices) {
            for (j in i + 1 until landmarks.size) {
                if (index >= EMBEDDING_SIZE) break
                embedding[index++] = distance(landmarks[i], landmarks[j]) / base
            }
        }
        return embedding
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    companion object {
        private const val EMBEDDING_SIZE = 20
        private val LANDMARK_TYPES = listOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM,
            FaceLandmark.LEFT_EAR,
            FaceLandmark.RIGHT_EAR,
            FaceLandmark.LEFT_CHEEK,
            FaceLandmark.RIGHT_CHEEK,
        )
    }
}
