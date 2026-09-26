package com.muslimedu.attendance.face

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The face numbers as they travel to the school server and into backup files. */
class FaceCodecTest {

    @Test
    fun `numbers survive base64 exactly`() {
        val embedding = FloatArray(192) { (it - 96) / 97f }
        assertArrayEquals(embedding, FaceCodec.fromBase64(FaceCodec.toBase64(embedding)), 0f)
    }

    @Test
    fun `little-endian float32, the same bytes the server's PHP pack('g*') makes`() {
        // pack('g*', 1.0, 2.0) in PHP = 00 00 80 3F 00 00 00 40
        assertEquals("AACAPwAAAEA=", FaceCodec.toBase64(floatArrayOf(1f, 2f)))
    }

    @Test
    fun `damaged numbers are refused`() {
        assertNull(FaceCodec.fromBase64("***"))
        assertNull(FaceCodec.fromBase64(""))
        assertNull(FaceCodec.fromBase64("AAE=")) // 2 bytes, not a whole float
    }

    @Test
    fun `a shared face angle must be exactly MobileFaceNet's 192 numbers`() {
        assertNull(FaceCodec.mobileFaceNetFromBase64(FaceCodec.toBase64(FloatArray(128) { 0.1f })))
        assertEquals(192, FaceCodec.mobileFaceNetFromBase64(FaceCodec.toBase64(FloatArray(192) { 0.1f }))?.size)
    }
}
