package com.muslimedu.attendance.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Wording and formats for the device-health report - kept pure so they're unit-tested. */
object DeviceHealth {
    /**
     * A phone clock further off than this gets a warning: gate scans are
     * stamped with the phone's time, so a wrong clock means wrong time in /
     * time out, wrong Late flags and wrong times in the parent texts.
     */
    const val CLOCK_SKEW_WARN_SECONDS = 5 * 60

    /** "This phone's clock is 12 min fast", or null when it's close enough. */
    fun clockWarning(skewSeconds: Int?): String? {
        if (skewSeconds == null || abs(skewSeconds) < CLOCK_SKEW_WARN_SECONDS) return null
        val minutes = abs(skewSeconds) / 60
        val amount = if (minutes >= 120) "${minutes / 60} h" else "$minutes min"
        return "This phone's clock is $amount ${if (skewSeconds > 0) "fast" else "slow"}"
    }

    /** "2026-09-26T07:42:10+08:00" - what the server parses. */
    fun isoTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
            .withNano(0)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** "just now", "4 min ago", "3 h ago", "2 days ago". */
    fun ago(millis: Long, now: Long): String {
        val minutes = ((now - millis) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}
