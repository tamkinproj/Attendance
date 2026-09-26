package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.entities.GateScanEntity
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The admin's gate schedule: [perDay] Coming In and [perDay] Going Out scans
 * per student per day, and the time each one opens - [inTimes][0] is when
 * the first Coming In opens, [outTimes][0] the first Going Out, and so on.
 * In order they run In 1 < Out 1 < In 2 < Out 2 ...
 *
 * [lateAfter] has one entry per Coming In: a Coming In scanned after that
 * time is Late. Null = that Coming In is never late (no late check) - which
 * is also what a schedule saved before late checks existed gets, so no
 * student is flagged by a time the admin never chose.
 */
data class GateScheduleConfig(
    val perDay: Int,
    val inTimes: List<LocalTime>,
    val outTimes: List<LocalTime>,
    val lateAfter: List<LocalTime?> = List(perDay) { null },
) {
    fun timesFor(direction: String): List<LocalTime> = if (direction == GateScanEntity.DIRECTION_IN) inTimes else outTimes

    /** When [direction] first opens today - the dashboard keeps that button locked until then. */
    fun firstOpening(direction: String): LocalTime = timesFor(direction).first()

    /** The late time for Coming In [number] (1-based), or null when it has no late check. */
    fun lateAfterFor(number: Int): LocalTime? = lateAfter.getOrNull(number - 1)

    val lateCheckOn: Boolean get() = lateAfter.any { it != null }
}

/** Whether a student may make the Coming In / Going Out scan they just tapped for, by the admin's gate schedule. */
sealed class GateScanCheck {
    /** Allowed: this becomes scan [number] of [perDay] in that direction today. */
    data class Allowed(val number: Int, val perDay: Int) : GateScanCheck()

    /** Same direction as the student's last scan today (a double tap, or a missed scan the other way). */
    data class SameAsLast(val last: GateScanEntity) : GateScanCheck()

    /** The student already has all [perDay] scans in this direction today. */
    data class LimitReached(val perDay: Int, val last: GateScanEntity) : GateScanCheck()

    /** The student's next scan in this direction is number [number], which opens at [opensAt]. */
    data class NotOpenYet(val number: Int, val opensAt: LocalTime) : GateScanCheck()

    /** No schedule yet - the admin has to set one before the gate is used. */
    data object NotSetUp : GateScanCheck()
}

/**
 * Scans alternate Coming In / Going Out, up to [GateScheduleConfig.perDay]
 * each way per day, and each one only from its opening time. A refused scan
 * is stopped before the face step and nothing is saved.
 *
 * Going Out is allowed without a Coming In first, so a student who forgot
 * to scan in can still be recorded leaving.
 */
object GateSchedule {
    const val MIN_PER_DAY = 1
    const val MAX_PER_DAY = 4

    /** [todayRecorded]: the student's face-confirmed scans today, any order. [config] null = not set up. */
    fun check(todayRecorded: List<GateScanEntity>, direction: String, config: GateScheduleConfig?, now: LocalTime): GateScanCheck {
        if (config == null) return GateScanCheck.NotSetUp
        val last = todayRecorded.maxByOrNull { it.scannedAt }
        if (last != null && last.direction == direction) return GateScanCheck.SameAsLast(last)
        val sameDirection = todayRecorded.filter { it.direction == direction }
        if (sameDirection.size >= config.perDay) {
            return GateScanCheck.LimitReached(config.perDay, sameDirection.maxBy { it.scannedAt })
        }
        val number = sameDirection.size + 1
        val opensAt = config.timesFor(direction).getOrNull(number - 1)
        if (opensAt != null && now < opensAt) return GateScanCheck.NotOpenYet(number, opensAt)
        return GateScanCheck.Allowed(number = number, perDay = config.perDay)
    }

    /**
     * Starting times for a new schedule; the admin adjusts them. Morning only:
     * in from 6:00, out from 11:00. Whole day: in 6:00, out 11:30 (lunch),
     * in 12:30, out 16:00. More: spread evenly from 6:00 to 17:00.
     */
    fun defaultTimes(perDay: Int): Pair<List<LocalTime>, List<LocalTime>> = when (perDay) {
        1 -> listOf(LocalTime.of(6, 0)) to listOf(LocalTime.of(11, 0))
        2 -> listOf(LocalTime.of(6, 0), LocalTime.of(12, 30)) to listOf(LocalTime.of(11, 30), LocalTime.of(16, 0))
        else -> {
            val slots = perDay * 2
            val stepMinutes = (11 * 60) / (slots - 1) / 30 * 30
            val all = (0 until slots).map { LocalTime.of(6, 0).plusMinutes((it * stepMinutes).toLong()) }
            all.filterIndexed { i, _ -> i % 2 == 0 } to all.filterIndexed { i, _ -> i % 2 == 1 }
        }
    }

    /** In 1 < Out 1 < In 2 < Out 2 ... - each opening time later than the one before it. */
    fun timesInOrder(inTimes: List<LocalTime>, outTimes: List<LocalTime>): Boolean {
        if (inTimes.size != outTimes.size || inTimes.isEmpty()) return false
        val sequence = inTimes.indices.flatMap { listOf(inTimes[it], outTimes[it]) }
        return sequence.zipWithNext().all { (a, b) -> a < b }
    }

    /**
     * How many minutes late Coming In [number] is at [time], or null when
     * it's on time, isn't a Coming In, or has no late check. Whole minutes:
     * "late after 7:30" means 7:30 is on time and 7:31 is 1 minute late.
     */
    fun minutesLate(config: GateScheduleConfig?, direction: String, number: Int, time: LocalTime): Int? {
        if (config == null || direction != GateScanEntity.DIRECTION_IN) return null
        val lateAfter = config.lateAfterFor(number) ?: return null
        val at = time.truncatedTo(ChronoUnit.MINUTES)
        return if (at > lateAfter) Duration.between(lateAfter, at).toMinutes().toInt() else null
    }

    /**
     * Starting late times for a new schedule; the admin adjusts them. The
     * first Coming In is late 90 minutes after it opens (6:00 -> 7:30),
     * later ones (back from lunch) 30 minutes after (12:30 -> 1:00 PM).
     * None when that wouldn't fall before its Going Out.
     */
    fun defaultLateAfter(inTimes: List<LocalTime>, outTimes: List<LocalTime>): List<LocalTime?> =
        inTimes.indices.map { i ->
            val candidate = inTimes[i].plusMinutes(if (i == 0) 90L else 30L)
            candidate.takeIf { it > inTimes[i] && it < outTimes.getOrElse(i) { LocalTime.MAX } }
        }

    /** Each late time falls between its Coming In's opening and the Going Out after it. Null (no check) is always fine. */
    fun lateAfterValid(inTimes: List<LocalTime>, outTimes: List<LocalTime>, lateAfter: List<LocalTime?>): Boolean =
        lateAfter.size == inTimes.size &&
            lateAfter.indices.all { i -> lateAfter[i]?.let { it >= inTimes[i] && it < outTimes[i] } ?: true }

    /**
     * Where the "not arrived" cutoff starts: an hour after the first Coming
     * In's late time (7:30 -> 8:30), else three hours after it opens
     * (6:00 -> 9:00) - always before the first Going Out.
     */
    fun defaultAbsenceCutoff(inTimes: List<LocalTime>, outTimes: List<LocalTime>, lateAfter: List<LocalTime?>): LocalTime {
        val first = inTimes.first()
        val out = outTimes.first()
        val candidate = lateAfter.firstOrNull()?.plusMinutes(60) ?: first.plusHours(3)
        return if (candidate > first && candidate < out) candidate else first.plusMinutes(first.until(out, ChronoUnit.MINUTES) / 2)
    }

    /**
     * Why [cutoff] doesn't fit the schedule, or null: it must be after the
     * first Coming In opens (and after its late time, if any - until then a
     * student isn't even late) and before the first Going Out.
     */
    fun absenceCutoffProblem(cutoff: LocalTime, inTimes: List<LocalTime>, outTimes: List<LocalTime>, lateAfter: List<LocalTime?>): String? {
        val first = inTimes.first()
        val late = lateAfter.firstOrNull()
        val out = outTimes.first()
        return when {
            cutoff <= first -> "The not-arrived time must be after Coming In 1 opens (${first.format(H_MM_A)})."
            late != null && cutoff <= late -> "The not-arrived time must be after Late after (${late.format(H_MM_A)}) - until then a student isn't even late."
            cutoff >= out -> "The not-arrived time must be before Going Out 1 opens (${out.format(H_MM_A)})."
            else -> null
        }
    }

    private val H_MM_A: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
}
