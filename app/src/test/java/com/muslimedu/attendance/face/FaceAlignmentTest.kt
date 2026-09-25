package com.muslimedu.attendance.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

/** The pure-Kotlin half of MobileFaceNetRecognizer: alignment onto the model's layout, and the match score. */
class FaceAlignmentTest {

    private val reference = FaceAlignment.REFERENCE_EYES + FaceAlignment.REFERENCE_MOUTH

    private fun assertNear(expected: Point2, actual: Point2, tolerance: Float = 0.05f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    @Test
    fun `a face already in the reference layout is left as it is`() {
        val t = FaceAlignment.fitSimilarity(reference, reference)!!
        assertEquals(1f, t.a, 1e-4f)
        assertEquals(0f, t.b, 1e-4f)
        assertEquals(0f, t.tx, 1e-3f)
        assertEquals(0f, t.ty, 1e-3f)
    }

    @Test
    fun `a tilted, scaled, shifted face is mapped back onto the reference points`() {
        // Put the reference face into a camera frame: 4.5x bigger, tilted 17 degrees, moved to (300, 420).
        val angle = Math.toRadians(17.0)
        val inFrame = Similarity(
            a = (4.5 * cos(angle)).toFloat(),
            b = (4.5 * sin(angle)).toFloat(),
            tx = 300f,
            ty = 420f,
        )
        val landmarks = reference.map(inFrame::apply)
        val t = FaceAlignment.transformFor(eyes = landmarks.take(2), mouth = landmarks.drop(2))!!
        reference.zip(landmarks).forEach { (expected, seen) -> assertNear(expected, t.apply(seen)) }
    }

    @Test
    fun `sides are taken from the image, not from the order ML Kit reports them`() {
        val eyes = FaceAlignment.REFERENCE_EYES
        val mouth = FaceAlignment.REFERENCE_MOUTH
        // ML Kit's LEFT_EYE is the subject's left - the image's right. Reversed order must not flip the face.
        val t = FaceAlignment.transformFor(eyes = eyes.reversed(), mouth = mouth.reversed())!!
        assertEquals(1f, t.a, 1e-4f)
        assertEquals(0f, t.b, 1e-4f)
    }

    @Test
    fun `the eyes alone are enough when the mouth corners are missing`() {
        val t = FaceAlignment.transformFor(eyes = FaceAlignment.REFERENCE_EYES.map { Point2(it.x * 2, it.y * 2) }, mouth = null)!!
        FaceAlignment.REFERENCE_EYES.forEach { assertNear(it, t.apply(Point2(it.x * 2, it.y * 2))) }
    }

    @Test
    fun `no alignment without two separate eyes`() {
        assertNull(FaceAlignment.transformFor(eyes = listOf(Point2(10f, 10f)), mouth = null))
        assertNull(FaceAlignment.transformFor(eyes = listOf(Point2(10f, 10f), Point2(10f, 10f)), mouth = null))
    }

    @Test
    fun `match score is 1 for the same embedding, about 0_5 for unrelated ones, 0 for opposite`() {
        val a = FaceAlignment.l2Normalize(floatArrayOf(3f, 4f, 0f))
        val b = FaceAlignment.l2Normalize(floatArrayOf(-4f, 3f, 0f))
        assertEquals(1f, FaceAlignment.matchScore(a, a), 1e-5f)
        assertEquals(0.5f, FaceAlignment.matchScore(a, b), 1e-5f)
        assertEquals(0f, FaceAlignment.matchScore(a, FloatArray(3) { -a[it] }), 1e-5f)
    }

    @Test
    fun `an old landmark template never matches a model embedding`() {
        assertEquals(0f, FaceAlignment.matchScore(FloatArray(20) { 1f }, FloatArray(192) { 1f }), 0f)
    }

    @Test
    fun `l2Normalize gives unit length`() {
        val v = FaceAlignment.l2Normalize(floatArrayOf(1f, 2f, 2f))
        assertEquals(1f, v.sumOf { (it * it).toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `the MobileFaceNet model ships in the APK assets unchanged`() {
        val model = File("src/main/assets/mobilefacenet.tflite")
        assertTrue("model file missing: ${model.absolutePath}", model.isFile)
        val sha = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b", sha)
    }
}
