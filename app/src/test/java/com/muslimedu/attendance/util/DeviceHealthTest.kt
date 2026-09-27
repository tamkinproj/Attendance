package com.muslimedu.attendance.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class DeviceHealthTest {

    @Test
    fun `a clock within five minutes is fine`() {
        assertNull(DeviceHealth.clockWarning(null))
        assertNull(DeviceHealth.clockWarning(0))
        assertNull(DeviceHealth.clockWarning(299))
        assertNull(DeviceHealth.clockWarning(-299))
    }

    @Test
    fun `a clock five minutes or more off says which way and how much`() {
        assertEquals("This phone's clock is 5 min fast", DeviceHealth.clockWarning(300))
        assertEquals("This phone's clock is 12 min slow", DeviceHealth.clockWarning(-12 * 60 - 30))
        assertEquals("This phone's clock is 3 h fast", DeviceHealth.clockWarning(3 * 3600 + 100))
    }

    @Test
    fun `times go to the server as ISO-8601 with the phone's offset`() {
        // 2026-09-26 07:42:10.500 in Manila (UTC+8)
        val millis = 1790379730500L
        assertEquals("2026-09-26T07:42:10+08:00", DeviceHealth.isoTime(millis, ZoneId.of("Asia/Manila")))
        assertEquals("2026-09-25T23:42:10Z", DeviceHealth.isoTime(millis, ZoneId.of("UTC")))
    }

    @Test
    fun `ago reads like a person would say it`() {
        val now = 10_000_000_000L
        assertEquals("just now", DeviceHealth.ago(now - 30_000, now))
        assertEquals("4 min ago", DeviceHealth.ago(now - 4 * 60_000, now))
        assertEquals("3 h ago", DeviceHealth.ago(now - 3 * 3_600_000, now))
        assertEquals("2 days ago", DeviceHealth.ago(now - 49 * 3_600_000L, now))
        assertEquals("just now", DeviceHealth.ago(now + 60_000, now))
    }
}
