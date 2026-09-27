package com.muslimedu.attendance.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kiosk mode's one deliberate animated flourish (see
 * [com.muslimedu.attendance.ui.screens.admin.KioskModeScreen]): a soft ring
 * that breathes outward and fades behind whatever it's applied to - meant
 * as a quiet "tap here" invitation on an otherwise idle screen, not a
 * status indicator (nothing reads its state, unlike
 * [FaceScanOverlay]'s mesh/progress, which really is driven by the live
 * capture). Two rings, the second started half a cycle behind the first
 * ([StartOffset]) so one is always fading in as the other fades out - the
 * same idea as a sonar ping repeating.
 *
 * Only ever applied when kiosk mode is actually on ([GateDashboardScreen]'s
 * and [GateScanScreen]'s own `kiosk` flag) - a phone or a kiosk-off tablet
 * never runs this at all, so this can't affect their look or battery use.
 */
@Composable
fun Modifier.kioskPulse(color: Color, maxExtra: Dp = 16.dp, strokeWidth: Dp = 2.5.dp): Modifier {
    val transition = rememberInfiniteTransition(label = "kioskPulse")
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "kioskPulseA",
    )
    val phaseB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            initialStartOffset = StartOffset(900, StartOffsetType.FastForward),
        ),
        label = "kioskPulseB",
    )
    return this.drawBehind {
        val extraPx = maxExtra.toPx()
        val strokePx = strokeWidth.toPx()
        val base = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        for (t in listOf(phaseA, phaseB)) {
            val alpha = (1f - t) * 0.5f
            if (alpha <= 0.01f) continue
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = base + extraPx * t,
                center = center,
                style = Stroke(width = strokePx),
            )
        }
    }
}
