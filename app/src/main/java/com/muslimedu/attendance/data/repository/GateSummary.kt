package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.entities.GateScanEntity
import java.time.DayOfWeek
import java.time.LocalDate

/** Which kind of period the Attendance Summary screen is showing. */
enum class GateSummaryPeriod { Week, Month }

/** A closed date range, both ends inclusive. */
data class GateSummaryRange(val start: LocalDate, val end: LocalDate)

/** One student's roll-up over the period: how many days they were present, and how many of those were late. */
data class StudentGateSummary(
    val code: String,
    val name: String?,
    val section: String?,
    val daysPresent: Int,
    val daysLate: Int,
    val lastPresentDate: String?,
)

data class GateSummaryTotals(
    val daysWithActivity: Int,
    val studentDaysPresent: Int,
    val studentDaysLate: Int,
    val faceFailedCount: Int,
    val students: List<StudentGateSummary>,
)

/**
 * A week/month roll-up of this device's own [GateScanEntity] rows, for the
 * on-device Attendance Summary screen - a phone-side complement to the
 * web's Gate Reports page, not a replacement for it.
 *
 * **Deliberately does not compute Absent.** `gate_scans` only ever holds
 * what THIS phone recorded (see [GateAttendanceRepository]'s own doc
 * comment - "other gates' are on the web admin") - at a school with more
 * than one gate phone, a day with nothing here might just mean the student
 * used a *different* device, not that they weren't at school. The web's
 * `GateReportService` can call a day Absent because it also knows the
 * school's active days, holidays, and every gate device's records; none of
 * that exists on one phone. Present/Late/Face-failed are safe to show here
 * because each one only ever adds a real recorded event - it never infers
 * one from silence the way Absent would have to.
 */
object GateSummary {

    /** The Monday-Sunday week, or the calendar month, that [anchor] falls in. */
    fun periodRange(period: GateSummaryPeriod, anchor: LocalDate): GateSummaryRange = when (period) {
        GateSummaryPeriod.Week -> {
            val start = anchor.with(DayOfWeek.MONDAY)
            GateSummaryRange(start, start.plusDays(6))
        }
        GateSummaryPeriod.Month -> {
            val start = anchor.withDayOfMonth(1)
            GateSummaryRange(start, start.plusMonths(1).minusDays(1))
        }
    }

    /** The anchor date one period before/after [anchor] - still just a date, [periodRange] turns it into a range. */
    fun shiftAnchor(period: GateSummaryPeriod, anchor: LocalDate, forward: Boolean): LocalDate = when (period) {
        GateSummaryPeriod.Week -> if (forward) anchor.plusWeeks(1) else anchor.minusWeeks(1)
        GateSummaryPeriod.Month -> if (forward) anchor.plusMonths(1) else anchor.minusMonths(1)
    }

    /** False once stepping forward from [anchor] would land on a period that hasn't started yet - capped at today, like the web's Gate Reports. */
    fun canStepForward(period: GateSummaryPeriod, anchor: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        val next = periodRange(period, shiftAnchor(period, anchor, forward = true))
        return !next.start.isAfter(today)
    }

    /** Aggregates every scan in the range into per-student Present/Late counts, worst-affected first. */
    fun build(scans: List<GateScanEntity>): GateSummaryTotals {
        val recorded = scans.filter { it.outcome == GateScanEntity.OUTCOME_RECORDED }
        val faceFailed = scans.count { it.outcome == GateScanEntity.OUTCOME_REJECTED }
        val byStudent = recorded.groupBy { it.studentCode }
        val students = byStudent.map { (code, studentScans) ->
            val byDate = studentScans.groupBy { it.scanDate }
            val latest = studentScans.maxByOrNull { it.scannedAt }
            StudentGateSummary(
                code = code,
                name = latest?.studentName,
                section = latest?.sectionName,
                daysPresent = byDate.size,
                daysLate = byDate.values.count { day -> day.any { it.late } },
                lastPresentDate = byDate.keys.maxOrNull(),
            )
        }.sortedWith(
            compareByDescending<StudentGateSummary> { it.daysLate }
                .thenByDescending { it.daysPresent }
                .thenBy { it.name ?: it.code },
        )
        return GateSummaryTotals(
            daysWithActivity = scans.map { it.scanDate }.distinct().size,
            studentDaysPresent = students.sumOf { it.daysPresent },
            studentDaysLate = students.sumOf { it.daysLate },
            faceFailedCount = faceFailed,
            students = students,
        )
    }
}
