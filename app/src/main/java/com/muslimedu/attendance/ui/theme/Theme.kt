package com.muslimedu.attendance.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Logo teal on white cards over a pale mint background - see Color.kt for why buttons use the darker [BrandPrimary]. */
private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandPrimaryDark,
    secondary = AccentSlate,
    onSecondary = Color.White,
    secondaryContainer = AccentSlateContainer,
    onSecondaryContainer = AccentSlate,
    tertiary = AccentGold,
    onTertiary = Color.White,
    tertiaryContainer = AccentGoldContainer,
    onTertiaryContainer = AccentGold,
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRedContainer,
    onErrorContainer = AccentRed,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondary,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    // Cards, dialogs and menus draw from these since Material3 1.2 - left
    // unset they fall back to the baseline lavender-grey, off-brand here.
    surfaceTint = BrandPrimary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7FBFA),
    surfaceContainer = Color(0xFFF2F8F6),
    surfaceContainerHigh = Color(0xFFECF5F2),
    surfaceContainerHighest = Color(0xFFE5F1ED),
)

/** A deliberate dark palette from the same teal - previously the stock Compose template purple. */
private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimaryOnDark,
    onPrimary = BrandPrimaryDark,
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = BrandPrimaryContainer,
    secondary = AccentSlateContainer,
    onSecondary = AccentSlate,
    tertiary = AccentGoldContainer,
    onTertiary = AccentGold,
    error = AccentRedContainer,
    onError = AccentRed,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    surfaceTint = BrandPrimaryOnDark,
    surfaceContainerLowest = Color(0xFF0B1614),
    surfaceContainerLow = Color(0xFF13201D),
    surfaceContainer = Color(0xFF172522),
    surfaceContainerHigh = Color(0xFF1C2B28),
    surfaceContainerHighest = Color(0xFF22332F),
)

/**
 * No dynamic (wallpaper) color: on Android 12+ it would replace the brand
 * teal with whatever the device wallpaper suggests.
 */
@Composable
fun MuslimEduAttendanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar blends into the mint/dark background rather than a
            // solid teal band, with icons dark-on-light / light-on-dark.
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
