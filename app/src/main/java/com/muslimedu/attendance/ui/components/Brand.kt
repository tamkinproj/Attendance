package com.muslimedu.attendance.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.R
import com.muslimedu.attendance.ui.theme.BrandTeal

/** The app logo mark (teal on transparent); [tint] recolors it, e.g. white on a teal header. */
@Composable
fun BrandLogo(size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    Image(
        painter = painterResource(R.drawable.brand_logo),
        contentDescription = null,
        colorFilter = tint?.let { ColorFilter.tint(it) },
        modifier = modifier.size(size),
    )
}

/**
 * Full-screen backdrop for the entry screens (splash, sign-in, sync):
 * the logo's own look - a soft teal glow from the top-right corner fading
 * into the mint background.
 */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Surface(modifier = modifier.fillMaxSize(), color = background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(BrandTeal.copy(alpha = 0.18f), Color.Transparent),
                            center = Offset(size.width, 0f),
                            radius = size.maxDimension * 0.75f,
                        ),
                    )
                },
            content = content,
        )
    }
}

/** Circular initials badge - used where a student/admin photo isn't available. */
@Composable
fun InitialsAvatar(name: String?, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val initials = name.orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
    Box(
        modifier = modifier.size(size).background(color.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = color,
            fontWeight = FontWeight.Bold,
            style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
        )
    }
}
