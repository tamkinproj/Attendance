package com.muslimedu.attendance.face

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Regression coverage for [yuv420ToNv21]'s stride arithmetic, since a wrong
 * row/pixel-stride offset silently produces a garbled or shifted image
 * rather than a crash - the kind of bug that only shows up as "face
 * detection mysteriously never triggers" on some real devices.
 */
class CameraFrameConversionTest {

    @Test
    fun `packed planes with no row padding`() {
        val width = 4
        val height = 2
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val u = ByteBuffer.wrap(byteArrayOf(9, 10))
        val v = ByteBuffer.wrap(byteArrayOf(11, 12))

        val result = yuv420ToNv21(
            width, height,
            y, yRowStride = width, yPixelStride = 1,
            u, uRowStride = 2, uPixelStride = 1,
            v, vRowStride = 2, vPixelStride = 1,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 11, 9, 12, 10), result)
    }

    @Test
    fun `semi-planar interleaved U V with pixelStride 2`() {
        val width = 4
        val height = 2
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        // A real semi-planar frame: one buffer holding U0,V0,U1,V1,...; the
        // "U plane" and "V plane" CameraX hands back are both views into it,
        // offset by one byte from each other, each read with pixelStride 2.
        val interleaved = byteArrayOf(20, 21, 22, 23)
        val uView = ByteBuffer.wrap(interleaved)
        val vView = ByteBuffer.wrap(interleaved.copyOfRange(1, interleaved.size))

        val result = yuv420ToNv21(
            width, height,
            y, yRowStride = width, yPixelStride = 1,
            uView, uRowStride = 2, uPixelStride = 2,
            vView, vRowStride = 2, vPixelStride = 2,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 20, 23, 22), result)
    }

    @Test
    fun `row-padded Y plane skips the padding bytes`() {
        val width = 4
        val height = 2
        val rowStride = 6 // 2 padding bytes per row
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99))
        val u = ByteBuffer.wrap(byteArrayOf(9, 10))
        val v = ByteBuffer.wrap(byteArrayOf(11, 12))

        val result = yuv420ToNv21(
            width, height,
            y, yRowStride = rowStride, yPixelStride = 1,
            u, uRowStride = 2, uPixelStride = 1,
            v, vRowStride = 2, vPixelStride = 1,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 11, 9, 12, 10), result)
    }
}
