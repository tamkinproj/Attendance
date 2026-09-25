package com.muslimedu.attendance.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Where the face guide sits in the camera view - shared by the overlay's drawing and nothing else. */
internal fun faceOvalRect(size: Size): Rect {
    val width = min(size.width * 0.70f, size.height * 0.46f)
    val height = width * 1.32f
    val center = Offset(size.width / 2f, size.height * 0.42f)
    return Rect(center.x - width / 2f, center.y - height / 2f, center.x + width / 2f, center.y + height / 2f)
}

/**
 * The full-screen face scan look: the camera shows through an oval face
 * guide (the rest dimmed), with a dashed outline, corner brackets, and -
 * once a face is in view - a monochrome face-mesh graph with a scan line
 * sweeping over it, and a progress ring around the oval.
 *
 * The mesh is a drawn pattern inside the guide, not the detected face's
 * landmarks: it shows that scanning is under way, it isn't a measurement.
 */
@Composable
fun FaceScanOverlay(
    faceSeen: Boolean,
    progress: Float,
    status: String,
    busy: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "faceScan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    val dashPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 48f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "dash",
    )
    val meshAlpha by animateFloatAsState(if (faceSeen || busy) 1f else 0f, tween(400), label = "mesh")
    val shownProgress by animateFloatAsState(if (busy) 1f else progress, tween(300), label = "progress")
    val mesh = remember { FaceMesh.build() }
    val light = lerp(accent, Color.White, 0.55f)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val oval = faceOvalRect(size)

            // Dim everything but the oval.
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(oval)
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.55f))

            if (meshAlpha > 0f) {
                val ovalPath = Path().apply { addOval(oval) }
                clipPath(ovalPath) {
                    drawMesh(mesh, oval, light, meshAlpha, sweep)
                    // The scan line.
                    val y = oval.top + oval.height * sweep
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to light.copy(alpha = 0.55f * meshAlpha),
                            1f to Color.Transparent,
                            startY = y - 36.dp.toPx(),
                            endY = y + 36.dp.toPx(),
                        ),
                        topLeft = Offset(oval.left, y - 36.dp.toPx()),
                        size = Size(oval.width, 72.dp.toPx()),
                    )
                }
            }

            // Dashed guide, solid in the accent once a face is in view.
            drawOval(
                color = if (faceSeen || busy) accent else Color.White,
                topLeft = oval.topLeft,
                size = oval.size,
                style = Stroke(
                    width = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()), dashPhase),
                ),
            )
            // Progress ring, starting at the chin.
            if (shownProgress > 0f) {
                val ring = oval.inflate(8.dp.toPx())
                drawArc(
                    color = accent,
                    startAngle = 90f,
                    sweepAngle = 360f * shownProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = ring.topLeft,
                    size = ring.size,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCornerBrackets(oval.inflate(28.dp.toPx()), Color.White)
        }

        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 88.dp),
        ) {
            Text(
                status,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

private fun DrawScope.drawCornerBrackets(rect: Rect, color: Color) {
    val arm = 30.dp.toPx()
    val stroke = 4.dp.toPx()
    listOf(
        Triple(rect.topLeft, 1f, 1f),
        Triple(rect.topRight, -1f, 1f),
        Triple(rect.bottomLeft, 1f, -1f),
        Triple(rect.bottomRight, -1f, -1f),
    ).forEach { (corner, dx, dy) ->
        drawLine(color, corner, Offset(corner.x + arm * dx, corner.y), stroke, StrokeCap.Round)
        drawLine(color, corner, Offset(corner.x, corner.y + arm * dy), stroke, StrokeCap.Round)
    }
}

/** Points brighten as the scan line passes them. */
private fun DrawScope.drawMesh(mesh: FaceMesh, oval: Rect, color: Color, alpha: Float, sweep: Float) {
    val cx = oval.center.x
    val cy = oval.center.y
    val rx = oval.width / 2f
    val ry = oval.height / 2f
    fun at(p: Offset) = Offset(cx + p.x * rx, cy + p.y * ry)
    val lineWidth = 1.dp.toPx()
    mesh.edges.forEach { (a, b) ->
        drawLine(color.copy(alpha = 0.45f * alpha), at(mesh.points[a]), at(mesh.points[b]), lineWidth)
    }
    val sweepY = sweep * 2f - 1f
    mesh.points.forEach { p ->
        val near = (1f - abs(p.y - sweepY) / 0.35f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = (0.55f + 0.45f * near) * alpha), radius = (2f + 1.5f * near).dp.toPx(), center = at(p))
    }
}

/** A face-shaped web: three rings following the oval plus eye, nose and mouth points, in -1..1 oval coordinates. */
private class FaceMesh(val points: List<Offset>, val edges: List<Pair<Int, Int>>) {
    companion object {
        fun build(): FaceMesh {
            val points = mutableListOf<Offset>()
            val rings = listOf(Triple(0.9f, 18, 0f), Triple(0.66f, 14, 0.2f), Triple(0.4f, 10, 0.1f))
            val ringStart = mutableListOf<Int>()
            rings.forEach { (r, n, offset) ->
                ringStart += points.size
                repeat(n) { i ->
                    val angle = 2 * PI * (i + offset) / n
                    points += Offset((r * cos(angle)).toFloat(), (r * sin(angle)).toFloat())
                }
            }
            val featureStart = points.size
            points += listOf(
                Offset(-0.36f, -0.2f), Offset(0.36f, -0.2f), // eyes
                Offset(-0.5f, -0.34f), Offset(0.5f, -0.34f), // brows
                Offset(0f, -0.12f), Offset(0f, 0.14f), // nose bridge, tip
                Offset(-0.26f, 0.44f), Offset(0.26f, 0.44f), Offset(0f, 0.52f), // mouth
            )
            val edges = mutableSetOf<Pair<Int, Int>>()
            fun link(a: Int, b: Int) {
                if (a != b) edges += if (a < b) a to b else b to a
            }
            fun nearest(from: Int, candidates: IntRange, count: Int) =
                candidates.sortedBy { hypot(points[it].x - points[from].x, points[it].y - points[from].y) }.take(count)

            rings.forEachIndexed { k, (_, n, _) ->
                val start = ringStart[k]
                repeat(n) { i -> link(start + i, start + (i + 1) % n) }
                val next = if (k + 1 < rings.size) ringStart[k + 1] until ringStart[k + 1] + rings[k + 1].second else featureStart until points.size
                repeat(n) { i -> nearest(start + i, next, 2).forEach { link(start + i, it) } }
            }
            val features = featureStart until points.size
            features.forEach { f -> nearest(f, features, 3).forEach { link(f, it) } }
            return FaceMesh(points, edges.toList())
        }
    }
}
