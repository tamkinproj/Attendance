package com.muslimedu.attendance.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the enrollment camera may capture each angle, from ML Kit's head yaw. */
class FaceAnglesTest {

    @Test
    fun `straight takes a face looking at the camera, either way a little`() {
        assertTrue(FaceAngles.accepts(FaceAngle.STRAIGHT, 0f, null))
        assertTrue(FaceAngles.accepts(FaceAngle.STRAIGHT, 12f, null))
        assertTrue(FaceAngles.accepts(FaceAngle.STRAIGHT, -11.5f, null))
        assertFalse(FaceAngles.accepts(FaceAngle.STRAIGHT, 20f, null))
        assertFalse(FaceAngles.accepts(FaceAngle.STRAIGHT, -25f, null))
    }

    @Test
    fun `a side is a real turn to either side, not a profile`() {
        assertFalse(FaceAngles.accepts(FaceAngle.SIDE, 5f, null))
        assertTrue(FaceAngles.accepts(FaceAngle.SIDE, 20f, null))
        assertTrue(FaceAngles.accepts(FaceAngle.SIDE, -20f, null))
        assertTrue(FaceAngles.accepts(FaceAngle.SIDE, 40f, null))
        assertFalse(FaceAngles.accepts(FaceAngle.SIDE, 60f, null))
    }

    @Test
    fun `the other side must be the opposite way from the first`() {
        assertFalse(FaceAngles.accepts(FaceAngle.OTHER_SIDE, 22f, firstSideYaw = 18f))
        assertTrue(FaceAngles.accepts(FaceAngle.OTHER_SIDE, -22f, firstSideYaw = 18f))
        assertTrue(FaceAngles.accepts(FaceAngle.OTHER_SIDE, 22f, firstSideYaw = -18f))
        assertFalse(FaceAngles.accepts(FaceAngle.OTHER_SIDE, -5f, firstSideYaw = 18f))
    }

    @Test
    fun `with the first side skipped, any side counts`() {
        assertTrue(FaceAngles.accepts(FaceAngle.OTHER_SIDE, 22f, firstSideYaw = null))
        assertTrue(FaceAngles.accepts(FaceAngle.OTHER_SIDE, -22f, firstSideYaw = null))
    }

    @Test
    fun `angles are captured straight first, then each side`() {
        assertEquals(listOf(FaceAngle.STRAIGHT, FaceAngle.SIDE, FaceAngle.OTHER_SIDE), FaceAngle.entries.toList())
    }

    @Test
    fun `a turned face of the same person still passes the same-person check`() {
        // Same person ~0.9, different people ~0.5 (FaceAlignment.matchScore): the bar sits between.
        assertTrue(FaceAngles.SAME_PERSON_MIN_SCORE in 0.55f..0.75f)
    }
}
