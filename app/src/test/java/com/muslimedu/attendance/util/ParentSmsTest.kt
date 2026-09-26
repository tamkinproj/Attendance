package com.muslimedu.attendance.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parent-number rule and the text rendering must match the server
 * (PhMobileNumber::normalize, GateSmsTemplate::render in the Laravel patch) -
 * the expected strings here are what the server produced for the same input.
 */
class ParentSmsTest {

    @Test
    fun `accepts the ways people type a PH mobile number and stores one form`() {
        assertEquals("09171234567", normalizePhMobile("09171234567"))
        assertEquals("09171234567", normalizePhMobile("0917 123 4567"))
        assertEquals("09171234567", normalizePhMobile("+63 917-123-4567"))
        assertEquals("09171234567", normalizePhMobile("639171234567"))
        assertEquals("09171234567", normalizePhMobile("9171234567"))
    }

    @Test
    fun `refuses what can't receive a text`() {
        assertNull(normalizePhMobile("02812345678")) // landline
        assertNull(normalizePhMobile("0917123456")) // one digit short
        assertNull(normalizePhMobile("12345"))
        assertNull(normalizePhMobile(""))
        assertNull(normalizePhMobile("+1 555 123 4567"))
    }

    @Test
    fun `formats a stored number for reading back`() {
        assertEquals("0917 123 4567", formatPhMobile("09171234567"))
    }

    @Test
    fun `default messages read like the school's own Filipino text`() {
        assertEquals(
            "Manhaj School: Ang inyong anak na si Malik Aziz ay pumasok sa paaralan ng 7:05 AM (Sep 26, 2026).",
            SmsTemplate.render(SmsTemplate.DEFAULT_IN, "Malik Aziz", "2026-001", "07:05", "2026-09-26", "Manhaj School"),
        )
        assertEquals(
            "Ang inyong anak na si Malik Aziz ay lumabas ng paaralan ng 3:30 PM (Sep 26, 2026).",
            SmsTemplate.render(SmsTemplate.DEFAULT_OUT, "Malik Aziz", "2026-001", "15:30", "2026-09-26", null),
        )
    }

    @Test
    fun `a late Coming In gets the late message`() {
        assertEquals(
            "Ang inyong anak na si Malik Aziz ay pumasok sa paaralan ng 7:52 AM (Sep 26, 2026) - huli ng 22 minuto.",
            SmsTemplate.render(SmsTemplate.DEFAULT_LATE, "Malik Aziz", "S1", "07:52", "2026-09-26", null, 22),
        )
    }

    @Test
    fun `the not-arrived text names the cutoff time`() {
        // Same text the server's GateAbsenceService sent in its test.
        assertEquals(
            "Manhaj Academy: Ang inyong anak na si Malik Aziz ay hindi pa pumapasok sa paaralan hanggang 9:00 AM (Sep 28, 2026).",
            SmsTemplate.render(SmsTemplate.DEFAULT_ABSENT, "Malik Aziz", "C-Malik Aziz", "09:00", "2026-09-28", "Manhaj Academy"),
        )
    }

    @Test
    fun `every placeholder is filled`() {
        assertEquals(
            "S: A (C1) 12:00 PM Jan 2, 2026",
            SmsTemplate.render("{school}: {student} ({code}) {time} {date}", "A", "C1", "12:00", "2026-01-02", "S"),
        )
    }

    @Test
    fun `counts SMS parts the way carriers bill them`() {
        assertEquals(0, SmsTemplate.segments(""))
        assertEquals(1, SmsTemplate.segments("x".repeat(160)))
        assertEquals(2, SmsTemplate.segments("x".repeat(161)))
        assertEquals(2, SmsTemplate.segments("x".repeat(306)))
        assertEquals(3, SmsTemplate.segments("x".repeat(307)))
    }
}
