package com.muslimedu.attendance.util

import android.content.Context

/**
 * Kiosk mode is tablet-only (see [com.muslimedu.attendance.ui.kiosk.KioskController]
 * and [com.muslimedu.attendance.ui.screens.admin.KioskModeScreen]) - a phone
 * mounted on a wall doesn't have the screen real estate for a landscape,
 * two-column gate layout, and the user only ever described a tablet on a
 * kiosk stand.
 *
 * [smallestScreenWidthDp] is the same number Android's own `sw600dp`
 * resource-qualifier convention uses to draw this line, and doesn't change
 * with rotation (it's the smaller of the two dimensions), so a device
 * doesn't flip between "tablet" and "not tablet" just by turning sideways.
 */
const val TABLET_SMALLEST_WIDTH_DP = 600

/** Pure logic - see [DeviceFormFactorTest] for the boundary check. */
fun isTabletFormFactor(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP

/** The real thing to call from app code - reads the live configuration. */
fun isTabletFormFactor(context: Context): Boolean =
    isTabletFormFactor(context.resources.configuration.smallestScreenWidthDp)
