package com.muslimedu.attendance.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of kiosk mode's tablet check that's pure logic - see
 * TABLET_SMALLEST_WIDTH_DP's own doc comment for why 600dp is the line.
 * The Context-reading overload can't be unit-tested here (no Android
 * framework in this module - see the project's other Robolectric-free
 * pure-JVM tests), so it's kept to one line precisely so this test covers
 * everything that could actually be wrong with it.
 */
class DeviceFormFactorTest {

    @Test
    fun `narrower than 600dp is not a tablet`() {
        assertFalse(isTabletFormFactor(599))
        assertFalse(isTabletFormFactor(360)) // a typical phone in portrait
        assertFalse(isTabletFormFactor(0))
    }

    @Test
    fun `exactly 600dp and above is a tablet`() {
        assertTrue(isTabletFormFactor(600))
        assertTrue(isTabletFormFactor(601))
        assertTrue(isTabletFormFactor(800)) // a typical 10-inch tablet
    }
}
