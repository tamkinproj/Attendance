package com.muslimedu.attendance.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette taken from the app logo: a single mid teal ([BrandTeal],
 * the logo's exact #369A8E) on a pale mint ground.
 *
 * The logo teal only reaches ~3.4:1 against white, too low for button
 * labels and body text, so interactive/text color is [BrandPrimary] - the
 * same hue darkened to 5.1:1. [BrandTeal] is for the logo itself, gradients,
 * large icons and decoration. Status accents are chosen to stay readable as
 * text on white (>= 4.5:1) and to be told apart from the teal: amber for
 * "out"/warnings, coral red for errors, slate for neutral info. "In"/success
 * uses the brand teal itself.
 */
val BrandTeal = Color(0xFF369A8E)
val BrandPrimary = Color(0xFF267A70)
val BrandPrimaryDark = Color(0xFF1B5A53)
val BrandPrimaryLight = Color(0xFF5DB8AB)
val BrandPrimaryContainer = Color(0xFFD3ECE7)

val AccentSuccess = BrandPrimary
val AccentSuccessContainer = BrandPrimaryContainer

val AccentGold = Color(0xFFA36A0E)
val AccentGoldContainer = Color(0xFFFBEBD0)

val AccentRed = Color(0xFFC2453D)
val AccentRedContainer = Color(0xFFFBE1DE)

val AccentSlate = Color(0xFF3D6680)
val AccentSlateContainer = Color(0xFFDCE8F0)

/** "Going out" - a clear blue, told apart from the teal "coming in" at a glance (5.3:1 on white). */
val AccentBlue = Color(0xFF2F63C8)
val AccentBlueContainer = Color(0xFFE2EBFB)

/** The face-capture frame's border - brighter than [BrandPrimary] so it stands out over a live camera feed. */
val ScanFrameGreen = Color(0xFF3FD0B5)

val SurfaceLight = Color(0xFFFFFFFF)
val BackgroundLight = Color(0xFFF5F8F8)
val SurfaceVariantLight = Color(0xFFE2F0EC)
val TextPrimary = Color(0xFF14302C)
val TextSecondary = Color(0xFF5C7571)
val OutlineLight = Color(0xFFCFE3DE)

// Dark scheme: the same teal lifted for contrast on a deep teal-black ground.
val BrandPrimaryOnDark = Color(0xFF7FD3C6)
val BrandPrimaryContainerDark = Color(0xFF1D4A44)
val BackgroundDark = Color(0xFF0E1A18)
val SurfaceDark = Color(0xFF15231F)
val SurfaceVariantDark = Color(0xFF1F302C)
val TextPrimaryDark = Color(0xFFE0EEEA)
val TextSecondaryDark = Color(0xFF9DB7B1)
val OutlineDark = Color(0xFF3A524D)

val Success = AccentSuccess
val Error = AccentRed
val Warning = AccentGold
