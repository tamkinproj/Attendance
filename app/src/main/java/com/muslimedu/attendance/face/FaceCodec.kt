package com.muslimedu.attendance.face

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * A face angle's numbers (the model's embedding) as bytes - little-endian
 * float32, the form stored encrypted on this phone - and as base64 for the
 * school server and the backup file. The server stores it as-is (see
 * StudentFace in the Laravel patch).
 */
object FaceCodec {
    fun toBytes(embedding: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat(it * Float.SIZE_BYTES) }
    }

    fun toBase64(embedding: FloatArray): String = Base64.getEncoder().encodeToString(toBytes(embedding))

    /** Null when [text] isn't base64 of whole float32 values (damaged or not a face). */
    fun fromBase64(text: String): FloatArray? {
        val bytes = runCatching { Base64.getDecoder().decode(text.trim()) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null
        return fromBytes(bytes)
    }
}

/** One angle of a face in a form that can leave this phone (school server, backup file). */
class PortableFaceAngle(val pose: Int, val embedding: FloatArray, val livenessScore: Float)

/** One student's whole registration: every angle, which model made it, and its version. */
class PortableFace(val model: String, val version: String, val angles: List<PortableFaceAngle>)
