package com.muslimedu.attendance.face

import kotlin.math.sqrt

/** A 2D point in image pixels - plain floats so this file needs no Android classes and runs in unit tests. */
data class Point2(val x: Float, val y: Float)

/**
 * Rotation + uniform scale + translation: `x' = a*x - b*y + tx`, `y' = b*x + a*y + ty`.
 * [a] and [b] are scale*cos and scale*sin of the rotation.
 */
data class Similarity(val a: Float, val b: Float, val tx: Float, val ty: Float) {
    fun apply(p: Point2) = Point2(a * p.x - b * p.y + tx, b * p.x + a * p.y + ty)
}

/**
 * Lines a face up the way MobileFaceNet was trained: the standard 112x112
 * ArcFace layout (eyes at y~52, mouth corners at y~92). The model compares
 * faces well only when they are aligned like this - a loose crop around the
 * face box is noticeably worse.
 */
object FaceAlignment {
    const val SIZE = 112

    /** Reference positions in the 112x112 output: image-left eye, image-right eye. */
    val REFERENCE_EYES = listOf(Point2(38.2946f, 51.6963f), Point2(73.5318f, 51.5014f))

    /** Image-left mouth corner, image-right mouth corner. */
    val REFERENCE_MOUTH = listOf(Point2(41.5493f, 92.3655f), Point2(70.7299f, 92.2041f))

    /**
     * The transform taking a face's landmarks onto the reference layout.
     * Sides are picked by x position, not by ML Kit's "left"/"right" (which
     * is the subject's side), so a mirrored front-camera frame lines up the
     * same way. [mouth] is optional: two eyes alone fix rotation and scale.
     * Null when the eyes are missing or on top of each other.
     */
    fun transformFor(eyes: List<Point2>, mouth: List<Point2>?): Similarity? {
        if (eyes.size != 2) return null
        val src = eyes.sortedBy { it.x }.toMutableList()
        val dst = REFERENCE_EYES.toMutableList()
        if (mouth != null && mouth.size == 2) {
            src += mouth.sortedBy { it.x }
            dst += REFERENCE_MOUTH
        }
        return fitSimilarity(src, dst)
    }

    /** Least-squares similarity transform mapping [src] onto [dst] (Umeyama, no reflection). */
    fun fitSimilarity(src: List<Point2>, dst: List<Point2>): Similarity? {
        require(src.size == dst.size && src.size >= 2)
        val sx = src.map { it.x }.average().toFloat()
        val sy = src.map { it.y }.average().toFloat()
        val dx = dst.map { it.x }.average().toFloat()
        val dy = dst.map { it.y }.average().toFloat()
        var norm = 0f
        var dotSum = 0f
        var crossSum = 0f
        for (i in src.indices) {
            val px = src[i].x - sx
            val py = src[i].y - sy
            val qx = dst[i].x - dx
            val qy = dst[i].y - dy
            norm += px * px + py * py
            dotSum += px * qx + py * qy
            crossSum += px * qy - py * qx
        }
        if (norm < 1e-6f) return null
        val a = dotSum / norm
        val b = crossSum / norm
        return Similarity(a, b, tx = dx - (a * sx - b * sy), ty = dy - (b * sx + a * sy))
    }

    /** Scales [v] to unit length, so the dot product of two embeddings is their cosine. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / n }
    }

    /**
     * 0-1 match score for two unit-length embeddings: `1 - squaredDistance / 4`,
     * i.e. `(1 + cosine) / 2` - the same scale as the model's reference app.
     * About 0.5 for two different people, 0.9+ for the same person in
     * similar conditions. Embeddings of different sizes (a template from an
     * older recognizer) never match.
     */
    fun matchScore(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return ((1f + dot) / 2f).coerceIn(0f, 1f)
    }
}
