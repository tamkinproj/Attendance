package com.muslimedu.attendance.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Converts a CameraX [ImageProxy] - the default YUV_420_888 output format,
 * read via the stable [ImageProxy.getPlanes] API (no `ExperimentalGetImage`
 * opt-in needed, unlike the raw `android.media.Image` accessor) - into an
 * upright [Bitmap], since [FaceRecognizer] only speaks Bitmap, not the
 * camera's native frame format.
 *
 * Does not close [this] - the caller received it from CameraX and owns that
 * lifecycle, typically after also deciding whether it's done with the image
 * for other reasons.
 *
 * One full JPEG encode/decode round trip per call, so callers must throttle
 * how often this runs per second rather than calling it on every analyzed
 * frame - see [com.muslimedu.attendance.ui.components.LiveFaceCaptureView].
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val nv21 = yuv420ToNv21(
        width = width,
        height = height,
        yBuffer = yPlane.buffer, yRowStride = yPlane.rowStride, yPixelStride = yPlane.pixelStride,
        uBuffer = uPlane.buffer, uRowStride = uPlane.rowStride, uPixelStride = uPlane.pixelStride,
        vBuffer = vPlane.buffer, vRowStride = vPlane.rowStride, vPixelStride = vPlane.pixelStride,
    )
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val bytes = out.toByteArray()
    val sensorOriented = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return sensorOriented
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(sensorOriented, 0, 0, sensorOriented.width, sensorOriented.height, matrix, true)
}

/**
 * Row/pixel-stride-aware YUV_420_888 -> NV21 repacking, kept as a plain
 * function over buffers/strides (rather than an [ImageProxy] extension) so
 * it's testable on a plain JVM without the Android SDK or a real camera
 * frame - see `CameraFrameConversionTest`.
 *
 * Doesn't assume the U and V planes are already interleaved in memory the
 * way NV21 wants (true on some devices, false on others) - reads every
 * chroma sample by its actual stride instead of copying plane buffers
 * wholesale.
 */
internal fun yuv420ToNv21(
    width: Int,
    height: Int,
    yBuffer: ByteBuffer,
    yRowStride: Int,
    yPixelStride: Int,
    uBuffer: ByteBuffer,
    uRowStride: Int,
    uPixelStride: Int,
    vBuffer: ByteBuffer,
    vRowStride: Int,
    vPixelStride: Int,
): ByteArray {
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    copyPlane(yBuffer, yRowStride, yPixelStride, width, height, nv21, 0)

    // 4:2:0 subsampling - one chroma sample per 2x2 luma block.
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    var offset = ySize
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            // NV21 interleaves as V,U (not U,V).
            nv21[offset++] = vBuffer.get(row * vRowStride + col * vPixelStride)
            nv21[offset++] = uBuffer.get(row * uRowStride + col * uPixelStride)
        }
    }
    return nv21
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    dest: ByteArray,
    destOffset: Int,
) {
    if (pixelStride == 1 && rowStride == width) {
        // Already packed with no padding - one bulk copy. Duplicate the
        // buffer first so this doesn't consume the original's position,
        // in case anything else still expects to read it from the start.
        buffer.duplicate().get(dest, destOffset, width * height)
        return
    }
    var offset = destOffset
    for (row in 0 until height) {
        for (col in 0 until width) {
            // Absolute get(index) - doesn't touch the buffer's position, so
            // it's safe to read in any order without tracking where a prior
            // relative read left off.
            dest[offset++] = buffer.get(row * rowStride + col * pixelStride)
        }
    }
}
