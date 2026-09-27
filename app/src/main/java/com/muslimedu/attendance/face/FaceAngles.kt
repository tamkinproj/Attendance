package com.muslimedu.attendance.face

import kotlin.math.abs
import kotlin.math.sign

/**
 * The angles captured for each student at enrollment, in order. The gate
 * matches a live face against every one and keeps the best score, so a
 * student who arrives with their head a little turned still matches -
 * fewer false rejections while the MobileFaceNet threshold is untested on
 * real phones.
 */
enum class FaceAngle(val title: String, val instruction: String) {
    STRAIGHT("Straight", "Look straight at the camera"),
    SIDE("One side", "Turn your head a little to one side"),
    OTHER_SIDE("Other side", "Now turn a little to the other side"),
}

/**
 * When a head turn counts as each angle, from ML Kit's head yaw (degrees,
 * the sign says which way). Deliberately not "left"/"right": the front
 * camera's preview is mirrored, so naming a direction confuses more than it
 * helps - any side, then the opposite one.
 */
object FaceAngles {
    const val STRAIGHT_MAX_YAW = 12f
    const val SIDE_MIN_YAW = 12f
    const val SIDE_MAX_YAW = 40f

    /**
     * A later angle must still be the same person as the straight one:
     * MobileFaceNet scores the same person ~0.9 and different people
     * ~0.5 (see FaceAlignment.matchScore), so this sits clearly between -
     * lower than the gate's match threshold because a turned face scores a
     * little lower than a straight one.
     */
    const val SAME_PERSON_MIN_SCORE = 0.62f

    /** [firstSideYaw]: the yaw the [FaceAngle.SIDE] capture was taken at, so the other side is the opposite way. */
    fun accepts(angle: FaceAngle, yaw: Float, firstSideYaw: Float?): Boolean = when (angle) {
        FaceAngle.STRAIGHT -> abs(yaw) <= STRAIGHT_MAX_YAW
        FaceAngle.SIDE -> abs(yaw) in SIDE_MIN_YAW..SIDE_MAX_YAW
        FaceAngle.OTHER_SIDE -> abs(yaw) in SIDE_MIN_YAW..SIDE_MAX_YAW &&
            (firstSideYaw == null || firstSideYaw == 0f || sign(yaw) != sign(firstSideYaw))
    }
}
