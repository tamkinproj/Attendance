package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.entities.GateScanEntity

/** Whether a student may make the Coming In / Going Out scan they just tapped for, by the admin's gate schedule. */
sealed class GateScanCheck {
    /** Allowed: this becomes scan [number] of [perDay] in that direction today. */
    data class Allowed(val number: Int, val perDay: Int) : GateScanCheck()

    /** Same direction as the student's last scan today (a double tap, or a missed scan the other way). */
    data class SameAsLast(val last: GateScanEntity) : GateScanCheck()

    /** The student already has all [perDay] scans in this direction today. */
    data class LimitReached(val perDay: Int, val last: GateScanEntity) : GateScanCheck()

    /** No schedule yet - the admin has to set one before the gate is used. */
    data object NotSetUp : GateScanCheck()
}

/**
 * The admin's gate schedule: how many times each student comes in and goes
 * out per day - 1 + 1 for a morning-only school, 2 + 2 for a whole day with
 * a lunch break. Scans alternate Coming In / Going Out, and a scan beyond
 * the day's count is refused before the face step (nothing is saved).
 *
 * Going Out is allowed without a Coming In first, so a student who forgot
 * to scan in can still be recorded leaving.
 */
object GateSchedule {
    const val MIN_PER_DAY = 1
    const val MAX_PER_DAY = 4

    /** [todayRecorded]: the student's face-confirmed scans today, any order. [perDay] null = not set up. */
    fun check(todayRecorded: List<GateScanEntity>, direction: String, perDay: Int?): GateScanCheck {
        if (perDay == null) return GateScanCheck.NotSetUp
        val last = todayRecorded.maxByOrNull { it.scannedAt }
        if (last != null && last.direction == direction) return GateScanCheck.SameAsLast(last)
        val sameDirection = todayRecorded.filter { it.direction == direction }
        if (sameDirection.size >= perDay) return GateScanCheck.LimitReached(perDay, sameDirection.maxBy { it.scannedAt })
        return GateScanCheck.Allowed(number = sameDirection.size + 1, perDay = perDay)
    }
}
