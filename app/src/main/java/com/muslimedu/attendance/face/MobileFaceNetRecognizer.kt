package com.muslimedu.attendance.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real face recognition: ML Kit finds the face and its eyes / mouth corners,
 * the face is aligned onto MobileFaceNet's 112x112 layout ([FaceAlignment]),
 * and the bundled MobileFaceNet model (`assets/mobilefacenet.tflite`, see
 * `assets/licenses/mobilefacenet-NOTICE.txt`) turns it into a 192-number
 * embedding. Two faces are compared with [FaceAlignment.matchScore].
 *
 * Checked in the sandbox with the same alignment and normalisation in
 * Python (tflite-runtime) on two people x two photos: same person scored
 * 0.91-0.93, different people 0.46-0.55. Not yet checked on a phone camera.
 *
 * Runs fully offline. Nothing leaves the device; only the embedding is
 * stored (encrypted, by FaceTemplateRepository), never the photo.
 */
@Singleton
class MobileFaceNetRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : FaceRecognizer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build(),
    )

    // An Interpreter isn't thread-safe; one model run at a time.
    private val interpreterLock = Mutex()
    private val interpreter: Interpreter by lazy {
        Interpreter(loadModel(), Interpreter.Options().setNumThreads(4))
    }

    override val canTellPeopleApart: Boolean = true

    override suspend fun enrollFace(bitmap: Bitmap): FaceTemplate? {
        val face = detectFirstFace(bitmap) ?: return null
        val embedding = embed(bitmap, face) ?: return null
        return FaceTemplate(embedding = embedding, livenessScore = LivenessDetector.score(face), yaw = face.headEulerAngleY)
    }

    /** One detection and one model run for the live frame, then the best score over every enrolled angle. */
    override suspend fun verifyFace(liveFrame: Bitmap, storedTemplates: List<FaceTemplate>): Float? {
        if (storedTemplates.isEmpty()) return null
        val face = detectFirstFace(liveFrame) ?: return null
        val embedding = embed(liveFrame, face) ?: return null
        return storedTemplates.maxOf { FaceAlignment.matchScore(embedding, it.embedding) }
    }

    override fun similarity(a: FaceTemplate, b: FaceTemplate): Float =
        FaceAlignment.matchScore(a.embedding, b.embedding)

    private suspend fun detectFirstFace(bitmap: Bitmap): Face? = suspendCancellableCoroutine { continuation ->
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces -> continuation.resume(faces.maxByOrNull { it.boundingBox.width() }) }
            .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }

    /** Null when ML Kit didn't find both eyes - there's nothing to align the face by. */
    private suspend fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        val eyes = listOfNotNull(face.point(FaceLandmark.LEFT_EYE), face.point(FaceLandmark.RIGHT_EYE))
        val mouth = listOfNotNull(face.point(FaceLandmark.MOUTH_LEFT), face.point(FaceLandmark.MOUTH_RIGHT))
            .takeIf { it.size == 2 }
        val transform = FaceAlignment.transformFor(eyes, mouth) ?: return null
        val aligned = alignedFace(bitmap, transform)
        return withContext(Dispatchers.Default) {
            val input = toModelInput(aligned)
            aligned.recycle()
            // The model takes a fixed batch of two faces; run this one twice and keep the first row.
            val output = Array(BATCH) { FloatArray(EMBEDDING_SIZE) }
            interpreterLock.withLock { interpreter.run(input, output) }
            FaceAlignment.l2Normalize(output[0])
        }
    }

    private fun Face.point(type: Int): Point2? = getLandmark(type)?.position?.let { Point2(it.x, it.y) }

    private fun alignedFace(source: Bitmap, t: Similarity): Bitmap {
        val out = Bitmap.createBitmap(FaceAlignment.SIZE, FaceAlignment.SIZE, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(t.a, -t.b, t.tx, t.b, t.a, t.ty, 0f, 0f, 1f))
        }
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** RGB, (value - 127.5) / 128, as float32 [2, 112, 112, 3] - the face written into both batch slots. */
    private fun toModelInput(face: Bitmap): ByteBuffer {
        val size = FaceAlignment.SIZE
        val pixels = IntArray(size * size)
        face.getPixels(pixels, 0, size, 0, 0, size, size)
        val buffer = ByteBuffer.allocateDirect(BATCH * size * size * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        repeat(BATCH) {
            for (p in pixels) {
                buffer.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                buffer.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                buffer.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun loadModel(): MappedByteBuffer = context.assets.openFd(MODEL_FILE).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private companion object {
        const val MODEL_FILE = "mobilefacenet.tflite"
        const val BATCH = 2
        const val EMBEDDING_SIZE = 192
    }
}
