package com.muslimedu.attendance.util

import android.content.Context

/**
 * Tablet vs phone, for the pieces of the UI that genuinely need extra
 * screen real estate - right now just the Admin nav rail
 * ([com.muslimedu.attendance.ui.navigation.AppRoot]'s `showAdminRail`).
 * Kiosk mode ([com.muslimedu.attendance.ui.kiosk.KioskController],
 * [com.muslimedu.attendance.ui.screens.admin.KioskModeScreen]) used to gate
 * on this too - a tablet on a stand was the only thing the user originally
 * described - but the user later asked for a phone to be able to run kiosk
 * mode as well, so kiosk mode no longer checks this at all; it's driven
 * purely by [com.muslimedu.attendance.data.local.DeviceSettings.kioskModeEnabled].
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
