package com.muslimedu.attendance.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.Surface
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.muslimedu.attendance.face.LivenessDetector
import com.muslimedu.attendance.face.toUprightBitmap
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandPrimaryContainer
import com.muslimedu.attendance.ui.theme.ScanFrameGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * An embedded, in-app camera preview that auto-captures a selfie once it
 * sees a plausibly live face for a few consecutive frames - no system camera
 * app, no manual shutter tap. Shared by face enrollment and the RFID flow's
 * face-verification step, so both get the same capture UX.
 *
 * The detector used here for the live gate (PERFORMANCE_MODE_FAST, but
 * landmarks on - [LivenessDetector] scores them, so turning them off silently
 * pins every frame's score to its floor and the gate never opens) only decides
 * *when* to hand a frame over - a cheap, throwaway
 * pass over "does this look like a live face right now." The delivered
 * bitmap still goes through [com.muslimedu.attendance.face.MobileFaceNetRecognizer]'s
 * own PERFORMANCE_MODE_ACCURATE detector for the real enroll/verify call the
 * caller makes afterwards; this view has no opinion on match/liveness
 * *scoring*, only on whether a frame is worth handing over at all.
 *
 * Everything here runs on-device: the camera frame never leaves the process,
 * there is no network call in this file.
 */
@Composable
fun LiveFaceCaptureView(
    onCaptured: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Fills [modifier]'s whole area with the camera under [FaceScanOverlay]
     * (the gate's face step). Off: a 3:4 framed preview with a status line
     * and progress bar (enrollment).
     */
    fullScreen: Boolean = false,
    /** Changing it re-arms the one-shot capture on the same running camera - an automatic retry without restarting the preview. */
    captureKey: Int = 0,
    /** Replaces the live status line (full screen only), e.g. "Checking face..." or why the last try didn't match. */
    message: String? = null,
    /** A captured frame is being checked: the overlay shows its full ring. */
    busy: Boolean = false,
    accent: Color = BrandPrimary,
    /**
     * Only frames whose head yaw (ML Kit's Euler Y, degrees) this accepts
     * count towards a capture - enrollment asks for a straight face, then a
     * turn to each side. Null: any angle (the gate).
     */
    acceptYaw: ((Float) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Camera permission is needed to verify your face",
                color = if (fullScreen) Color.White else Color.Unspecified,
                modifier = Modifier.padding(top = if (fullScreen) 120.dp else 0.dp),
            )
            OutlinedButton(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Grant Camera Permission")
            }
        }
        return
    }

    var statusText by remember { mutableStateOf(PROMPT_NO_FACE) }
    // Purely cosmetic - drives the progress bar below, derived from the same
    // consecutive-good-frames count the gate itself already tracks. Never
    // read by the gate/capture logic, so this can't affect when a capture
    // actually fires.
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var captured by remember { mutableStateOf(false) }
    var faceSeen by remember { mutableStateOf(false) }

    // Best face seen so far, kept for the timeout fallback in the analyzer
    // below - there is no manual shutter button any more, so without this a
    // face that never quite clears the top liveness tier (glasses, dim room,
    // a device that reports no eye-open probability at all) would leave this
    // screen stuck with no way forward.
    var bestFrame by remember { mutableStateOf<Bitmap?>(null) }
    var bestScore by remember { mutableFloatStateOf(0f) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val previewView = remember {
        PreviewView(context).apply {
            // Without explicit MATCH_PARENT params the view is laid out at its
            // own natural size inside the Box below rather than filling it,
            // and what you see is an off-centre slice of the camera feed - in
            // practice the ceiling, with the subject's face cropped out of
            // view entirely.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val fastDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // LANDMARK_MODE_ALL is required, not optional: ML Kit defaults
                // to LANDMARK_MODE_NONE, which leaves Face.getAllLandmarks()
                // empty, which makes LivenessDetector.score() return its
                // "not enough landmarks" floor of 0.3 for *every* frame - below
                // LIVENESS_GATE_SCORE, so auto-capture could never fire on any
                // device in any lighting. Keep these two in sync: the gate
                // scores what this detector is configured to report.
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build(),
        )
    }
    val gate = remember { AutoCaptureGate() }
    // Read on the analysis thread; updated here so a new angle applies to the running camera.
    SideEffect { gate.acceptYaw = acceptYaw }

    // A new captureKey starts a fresh capture on the camera that's already running.
    LaunchedEffect(captureKey) {
        captured = false
        bestFrame = null
        bestScore = 0f
        startedAt = System.currentTimeMillis()
        gate.consecutiveGoodFrames = 0
        progressFraction = 0f
        statusText = PROMPT_NO_FACE
    }

    fun deliver(bitmap: Bitmap) {
        if (captured) return
        captured = true
        statusText = "Captured"
        onCaptured(bitmap)
    }

    DisposableEffect(previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                // Both use cases otherwise default to whatever the display
                // rotation happened to be when they were built, which is not
                // necessarily this view's - an analysis frame rotated the wrong
                // way is a face ML Kit is much less likely to find.
                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    analyzeFrame(imageProxy, gate, fastDetector) { bitmap, score, angleOk ->
                        scope.launch(Dispatchers.Main.immediate) {
                            if (captured) return@launch

                            faceSeen = score != null
                            // The timeout fallback below may only settle for a frame at the asked angle.
                            if (score != null && angleOk && score > bestScore) {
                                bestScore = score
                                bestFrame = bitmap
                            }

                            val goodFrames = gate.consecutiveGoodFrames
                            val heldOutFor = System.currentTimeMillis() - startedAt
                            val settleForBest = heldOutFor >= AutoCaptureGate.FALLBACK_AFTER_MS &&
                                bestScore >= AutoCaptureGate.FALLBACK_MIN_SCORE

                            statusText = when {
                                score == null -> PROMPT_NO_FACE
                                !angleOk -> "Turn your head as asked"
                                goodFrames > 0 -> "Hold still... ($goodFrames/${AutoCaptureGate.REQUIRED_FRAMES})"
                                else -> "Hold still - keep your eyes open"
                            }
                            progressFraction = when {
                                score == null -> 0f
                                else -> (goodFrames.toFloat() / AutoCaptureGate.REQUIRED_FRAMES).coerceIn(0f, 1f)
                            }

                            when {
                                goodFrames >= AutoCaptureGate.REQUIRED_FRAMES -> deliver(bitmap)
                                // Never captures a frame no face was found in:
                                // bestScore only rises above the floor once a
                                // face has actually been detected in one.
                                settleForBest -> bestFrame?.let(::deliver)
                                else -> Unit
                            }
                        }
                    }
                }
                try {
                    // Prefer the front (selfie) camera, but some low-cost tablets
                    // deployed for this app only have a back one - fall back
                    // rather than fail outright.
                    val hasFrontCamera = try {
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    } catch (e: Exception) {
                        false
                    }
                    val selector = if (hasFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main.immediate) { statusText = "Could not start camera: ${e.message}" }
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            providerFuture.get().unbindAll()
            analysisExecutor.shutdown()
            fastDetector.close()
        }
    }

    if (fullScreen) {
        Box(modifier = modifier.background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
            FaceScanOverlay(
                faceSeen = faceSeen,
                progress = progressFraction,
                status = message ?: if (faceSeen) "Scanning..." else "Position your face in the oval",
                busy = busy,
                accent = accent,
                modifier = Modifier.matchParentSize(),
            )
        }
        return
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .border(width = 3.dp, color = ScanFrameGreen, shape = RoundedCornerShape(20.dp)),
        ) {
            // matchParentSize, not fillMaxWidth: the latter leaves the height
            // to wrap content, so the preview is laid out at its own natural
            // size instead of filling this 3:4 frame.
            AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
        }

        Text(
            text = "Scanning your face",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        LinearProgressIndicator(
            progress = { progressFraction },
            color = BrandPrimary,
            trackColor = BrandPrimaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = "Keep your face centered inside the frame and keep looking forward",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

private const val PROMPT_NO_FACE = "Position your face in the frame"

/** Mutated across two threads (the analysis executor and ML Kit's callback thread) - not just Compose state. */
private class AutoCaptureGate {
    @Volatile var lastProcessedAt = 0L

    @Volatile var consecutiveGoodFrames = 0

    /** See LiveFaceCaptureView's acceptYaw. */
    @Volatile var acceptYaw: ((Float) -> Boolean)? = null

    companion object {
        const val MIN_FRAME_INTERVAL_MS = 300L
        const val REQUIRED_FRAMES = 3

        /** The "confidently live" tier from [LivenessDetector.score] - see its own doc comment for what this heuristic can and can't catch. */
        const val LIVENESS_GATE_SCORE = 0.9f

        /**
         * [LivenessDetector.score]'s "landmarks resolved, but no usable
         * eye-open signal" tier - a real, well-detected face, just not one
         * this crude heuristic can call confidently live. Good enough to
         * capture on the fallback path below, since what actually gets
         * *accepted* is decided afterwards: enrollment re-scores the captured
         * frame against the configured liveness threshold (see
         * FaceTemplateRepository) and verification still has to match the
         * stored template. This gate only picks which frame to hand over.
         */
        const val FALLBACK_MIN_SCORE = 0.5f

        /** How long to hold out for [LIVENESS_GATE_SCORE] before settling for the best face seen so far. */
        const val FALLBACK_AFTER_MS = 6_000L
    }
}

/**
 * Runs on the analysis executor thread - never touches Compose state
 * directly, only via [onResult], which receives the frame's
 * [LivenessDetector] score, or null if no face was found in it at all.
 * Closes [imageProxy] once done with it, either immediately (throttled/failed
 * frames) or once ML Kit's async detection completes.
 */
private fun analyzeFrame(
    imageProxy: ImageProxy,
    gate: AutoCaptureGate,
    detector: FaceDetector,
    onResult: (bitmap: Bitmap, livenessScore: Float?, angleOk: Boolean) -> Unit,
) {
    val now = System.currentTimeMillis()
    if (now - gate.lastProcessedAt < AutoCaptureGate.MIN_FRAME_INTERVAL_MS) {
        imageProxy.close()
        return
    }
    gate.lastProcessedAt = now

    val bitmap = try {
        imageProxy.toUprightBitmap()
    } catch (e: Exception) {
        imageProxy.close()
        return
    }

    detector.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { faces ->
            val face = faces.firstOrNull()
            val score = face?.let { LivenessDetector.score(it) }
            val angleOk = face != null && (gate.acceptYaw?.invoke(face.headEulerAngleY) ?: true)
            val isLive = score != null && angleOk && score >= AutoCaptureGate.LIVENESS_GATE_SCORE
            gate.consecutiveGoodFrames = if (isLive) gate.consecutiveGoodFrames + 1 else 0
            onResult(bitmap, score, angleOk)
        }
        .addOnFailureListener {
            gate.consecutiveGoodFrames = 0
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
