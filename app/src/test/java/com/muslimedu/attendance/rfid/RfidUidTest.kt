package com.muslimedu.attendance.rfid

import org.junit.Assert.assertEquals
import org.junit.Test

class RfidUidTest {

    @Test
    fun `a card reads the same however the reader types it`() {
        assertEquals("04:A1:B2:C3", normalizeRfidUid(" 04:a1:b2:c3\n"))
        assertEquals(normalizeRfidUid("04a1b2c3"), normalizeRfidUid("04A1B2C3"))
    }

    @Test
    fun `digits-only UIDs are unchanged`() {
        assertEquals("0012345678", normalizeRfidUid("0012345678"))
    }
}
