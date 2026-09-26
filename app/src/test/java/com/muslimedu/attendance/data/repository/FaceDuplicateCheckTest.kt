package com.muslimedu.attendance.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One face per student: the decision [FaceTemplateRepository.captureAngle] makes for each captured angle. */
class FaceDuplicateCheckTest {

    private val threshold = 0.85f

    @Test
    fun `a face matching another student at the gate threshold is a duplicate`() {
        assertEquals(7 to 0.85f, closestOtherStudent(mapOf(7 to 0.85f, 8 to 0.40f), ownStudentId = 1, threshold = threshold))
    }

    @Test
    fun `the closest of several matching students is named`() {
        val result = closestOtherStudent(mapOf(7 to 0.90f, 8 to 0.97f, 9 to 0.86f), ownStudentId = 1, threshold = threshold)
        assertEquals(8 to 0.97f, result)
    }

    @Test
    fun `below the threshold is a different person`() {
        assertNull(closestOtherStudent(mapOf(7 to 0.84f, 8 to 0.10f), ownStudentId = 1, threshold = threshold))
    }

    @Test
    fun `re-enrolling a student never matches their own old face`() {
        assertNull(closestOtherStudent(mapOf(1 to 0.99f), ownStudentId = 1, threshold = threshold))
        assertEquals(7 to 0.90f, closestOtherStudent(mapOf(1 to 0.99f, 7 to 0.90f), ownStudentId = 1, threshold = threshold))
    }

    @Test
    fun `the first face in a school has nothing to duplicate`() {
        assertNull(closestOtherStudent(emptyMap(), ownStudentId = 1, threshold = threshold))
    }

    @Test
    fun `each student with several angles counts at their best angle`() {
        val scores = listOf(7 to 0.40f, 7 to 0.88f, 7 to 0.52f, 8 to 0.30f, 9 to 0.84f)
        assertEquals(mapOf(7 to 0.88f, 8 to 0.30f, 9 to 0.84f), bestPerStudent(scores))
        assertEquals(7 to 0.88f, closestOtherStudent(bestPerStudent(scores), ownStudentId = 1, threshold = threshold))
    }

    @Test
    fun `no stored angles means no scores`() {
        assertEquals(emptyMap<Int, Float>(), bestPerStudent(emptyList()))
    }
}
