package com.muslimedu.attendance.face

import android.graphics.Bitmap
import android.graphics.PointF

/**
 * Abstraction over face detection + verification, so the rest of the app
 * doesn't depend on ML Kit (or whatever embedding model eventually backs
 * [extractTemplate]) directly.
 *
 * IMPORTANT caveat, read before treating this as production anti-fraud:
 * ML Kit's Face Detection API (the only real, working piece of this
 * interface right now) does face *detection* - a bounding box, landmarks,
 * head-pose angles, eye-open/smiling probabilities - not face *recognition*.
 * It has no embedding output. Real 1:1 verification needs a separate
 * TensorFlow Lite face-embedding model (e.g. MobileFaceNet) run on the
 * cropped/aligned face, and no such model file exists in this project yet.
 * [MlKitFaceRecognizer.extractTemplate] is a geometric-landmark placeholder
 * standing in for that until a real model is bundled - see its doc comment.
 */
interface FaceRecognizer {
    /** Detects a face in [bitmap] and returns its landmarks/signals, or null if none found. */
    suspend fun captureFace(bitmap: Bitmap): FaceData?

    /** Turns detected face data into a comparable [FaceTemplate]. */
    suspend fun extractTemplate(faceData: FaceData): FaceTemplate

    /** Detects a face in [liveFrame] and compares it against [storedTemplate]. Returns a 0-1 match score. */
    suspend fun verifyFace(liveFrame: Bitmap, storedTemplate: FaceTemplate): Float?

    /** Convenience: detect + extract in one call, for enrollment. */
    suspend fun enrollFace(bitmap: Bitmap): FaceTemplate?
}

data class FaceData(
    val bitmap: Bitmap,
    val landmarks: List<PointF>,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val headEulerAngleY: Float,
    val livenessScore: Float,
)

data class FaceTemplate(
    val embedding: FloatArray,
    val livenessScore: Float,
    val encryptionVersion: Int = 1,
)
