package com.muslimedu.attendance.face

import com.google.mlkit.vision.face.Face

/**
 * Basic (not adversarially robust) liveness heuristic from ML Kit's own
 * detection signals - a real anti-spoofing check would need texture/depth
 * analysis or a dedicated liveness model, neither of which ML Kit provides.
 * This catches the crude case (a flat printed photo generally won't have
 * ML Kit report plausible eye-open probabilities or enough landmark points)
 * without pretending to defeat a determined attacker with e.g. a video replay.
 *
 * Only uses [Face.getAllLandmarks] - not contours, which
 * [MlKitFaceRecognizer]'s detector never enables (CONTOUR_MODE is a separate,
 * costlier detection pass this app has no other use for), so
 * [Face.getAllContours] is always empty here.
 */
object LivenessDetector {
    private const val MIN_LANDMARKS = 5

    fun score(face: Face): Float {
        if (face.allLandmarks.size < MIN_LANDMARKS) return 0.3f

        val leftEyeOpen = face.leftEyeOpenProbability
        val rightEyeOpen = face.rightEyeOpenProbability
        val hasEyeSignal = leftEyeOpen != null && rightEyeOpen != null

        return when {
            !hasEyeSignal -> 0.5f
            leftEyeOpen!! > 0.4f && rightEyeOpen!! > 0.4f -> 0.9f
            else -> 0.6f
        }
    }
}
