package com.muslimedu.attendance.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette matching the reference UI mockup the user supplied: a
 * white/light-card look on a deep violet-purple brand color, with gold,
 * teal, and red used consistently as status accents (present/pending,
 * warning/late, and error/absent respectively) across every screen -
 * attendance badges, sync status, gate direction chips, stat cards.
 */
val BrandPurple = Color(0xFF6C3CE0)
val BrandPurpleDark = Color(0xFF4E28B0)
val BrandPurpleLight = Color(0xFF8F6BF0)
val BrandPurpleContainer = Color(0xFFEDE6FB)

val AccentGold = Color(0xFFF2A93B)
val AccentGoldContainer = Color(0xFFFCE9CC)

val AccentTeal = Color(0xFF2EC4B6)
val AccentTealContainer = Color(0xFFDBF5F2)

val AccentRed = Color(0xFFEF5350)
val AccentRedContainer = Color(0xFFFBE0DF)

/** The face-detection scan frame's border color in the reference mockup - a brighter, more saturated green than [AccentTeal]'s status-badge use. */
val ScanFrameGreen = Color(0xFF34D399)

val SurfaceLight = Color(0xFFFFFFFF)
val BackgroundLight = Color(0xFFF7F6FB)
val TextPrimary = Color(0xFF1F1B2E)
val TextSecondary = Color(0xFF8E8A9B)
val OutlineLight = Color(0xFFE7E3F2)

// Legacy Compose-template values - still referenced by DarkColorScheme in
// Theme.kt for the system-dark-mode fallback.
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Success = AccentTeal
val Error = AccentRed
val Warning = AccentGold
