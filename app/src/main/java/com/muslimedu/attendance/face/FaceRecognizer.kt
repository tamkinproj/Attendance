package com.muslimedu.attendance.face

import android.graphics.Bitmap

/**
 * Face recognition behind one interface, so the rest of the app doesn't
 * depend on ML Kit or the embedding model directly. The implementation is
 * [MobileFaceNetRecognizer]: ML Kit face detection + a bundled MobileFaceNet
 * TensorFlow Lite model. Liveness is still only [LivenessDetector]'s basic
 * heuristic - a good photo or video of a student can still fool it.
 */
interface FaceRecognizer {
    /** Detects the face in [bitmap] and turns it into a template, or null if no usable face was found. */
    suspend fun enrollFace(bitmap: Bitmap): FaceTemplate?

    /** Detects the face in [liveFrame] and compares it with [storedTemplate]. A 0-1 match score, or null if no face. */
    suspend fun verifyFace(liveFrame: Bitmap, storedTemplate: FaceTemplate): Float?

    /** 0-1 similarity between two stored templates, on the same scale as [verifyFace]. */
    fun similarity(a: FaceTemplate, b: FaceTemplate): Float

    /**
     * Whether [similarity] actually tells two different people apart. The
     * duplicate-face check at enrollment (one face per student) only runs
     * when this is true: on a recognizer that scores any two faces as a
     * match it would refuse every student after the first.
     */
    val canTellPeopleApart: Boolean
}

data class FaceTemplate(
    val embedding: FloatArray,
    val livenessScore: Float,
    val encryptionVersion: Int = 1,
)
