package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.DIRECTION_IN
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.OUTCOME_RECORDED
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.OUTCOME_REJECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Attendance Summary screen's pure logic: period ranges, stepping, and per-student aggregation. */
class GateSummaryTest {

    private var clock = 0L

    private fun scan(
        code: String,
        name: String,
        date: String,
        outcome: String = OUTCOME_RECORDED,
        late: Boolean = false,
    ) = GateScanEntity(
        schoolId = 1,
        studentCode = code,
        studentName = name,
        sectionName = "Grade 5A",
        direction = DIRECTION_IN,
        scanDate = date,
        scanTime = "07:40",
        scannedAt = clock++,
        verifiedByFace = outcome == OUTCOME_RECORDED,
        faceMatchScore = if (outcome == OUTCOME_RECORDED) 0.9f else null,
        outcome = outcome,
        late = late,
    )

    // ── periodRange ──────────────────────────────────────────────────────

    @Test
    fun `week range is the Monday-Sunday containing the anchor`() {
        // 2026-09-24 is a Thursday.
        val range = GateSummary.periodRange(GateSummaryPeriod.Week, LocalDate.parse("2026-09-24"))
        assertEquals(LocalDate.parse("2026-09-21"), range.start) // Monday
        assertEquals(LocalDate.parse("2026-09-27"), range.end) // Sunday
    }

    @Test
    fun `week range for a Monday anchor starts on itself`() {
        val range = GateSummary.periodRange(GateSummaryPeriod.Week, LocalDate.parse("2026-09-21"))
        assertEquals(LocalDate.parse("2026-09-21"), range.start)
        assertEquals(LocalDate.parse("2026-09-27"), range.end)
    }

    @Test
    fun `month range is the full calendar month`() {
        val range = GateSummary.periodRange(GateSummaryPeriod.Month, LocalDate.parse("2026-02-15"))
        assertEquals(LocalDate.parse("2026-02-01"), range.start)
        assertEquals(LocalDate.parse("2026-02-28"), range.end) // not a leap year
    }

    // ── shiftAnchor / canStepForward ─────────────────────────────────────

    @Test
    fun `shifting a week moves seven days`() {
        val anchor = LocalDate.parse("2026-09-24")
        assertEquals(LocalDate.parse("2026-10-01"), GateSummary.shiftAnchor(GateSummaryPeriod.Week, anchor, forward = true))
        assertEquals(LocalDate.parse("2026-09-17"), GateSummary.shiftAnchor(GateSummaryPeriod.Week, anchor, forward = false))
    }

    @Test
    fun `shifting a month moves to the same day next-or-previous month`() {
        val anchor = LocalDate.parse("2026-09-24")
        assertEquals(LocalDate.parse("2026-10-24"), GateSummary.shiftAnchor(GateSummaryPeriod.Month, anchor, forward = true))
        assertEquals(LocalDate.parse("2026-08-24"), GateSummary.shiftAnchor(GateSummaryPeriod.Month, anchor, forward = false))
    }

    @Test
    fun `stepping forward is capped at today, like the web's Gate Reports`() {
        val today = LocalDate.parse("2026-09-24") // within this week
        assertTrue(GateSummary.canStepForward(GateSummaryPeriod.Week, today, today))
        val nextWeekAnchor = GateSummary.shiftAnchor(GateSummaryPeriod.Week, today, forward = true)
        assertFalse(GateSummary.canStepForward(GateSummaryPeriod.Week, nextWeekAnchor, today))
    }

    @Test
    fun `stepping back is never capped`() {
        val today = LocalDate.parse("2026-09-24")
        val lastYear = today.minusYears(1)
        assertTrue(GateSummary.canStepForward(GateSummaryPeriod.Month, lastYear, today))
    }

    // ── build ────────────────────────────────────────────────────────────

    @Test
    fun `build counts nothing from an empty range`() {
        val totals = GateSummary.build(emptyList())
        assertEquals(0, totals.daysWithActivity)
        assertEquals(0, totals.studentDaysPresent)
        assertEquals(0, totals.studentDaysLate)
        assertEquals(0, totals.faceFailedCount)
        assertTrue(totals.students.isEmpty())
    }

    @Test
    fun `two recorded days for one student count as two days present`() {
        val totals = GateSummary.build(
            listOf(
                scan("STU001", "Ayham Yusop", "2026-09-21"),
                scan("STU001", "Ayham Yusop", "2026-09-22"),
            ),
        )
        assertEquals(1, totals.students.size)
        val student = totals.students.single()
        assertEquals(2, student.daysPresent)
        assertEquals(0, student.daysLate)
        assertEquals("2026-09-22", student.lastPresentDate)
        assertEquals(2, totals.studentDaysPresent)
    }

    @Test
    fun `a late scan counts the whole day as late, not the scan`() {
        val totals = GateSummary.build(
            listOf(
                scan("STU001", "Ayham Yusop", "2026-09-21", late = true),
                scan("STU001", "Ayham Yusop", "2026-09-22", late = false),
            ),
        )
        val student = totals.students.single()
        assertEquals(2, student.daysPresent)
        assertEquals(1, student.daysLate)
        assertEquals(1, totals.studentDaysLate)
    }

    @Test
    fun `a rejected face check never counts as present, only face-failed`() {
        val totals = GateSummary.build(
            listOf(
                scan("STU001", "Ayham Yusop", "2026-09-21", outcome = OUTCOME_REJECTED),
            ),
        )
        assertTrue(totals.students.isEmpty())
        assertEquals(1, totals.faceFailedCount)
        assertEquals(0, totals.studentDaysPresent)
    }

    @Test
    fun `students with more late days sort first`() {
        val totals = GateSummary.build(
            listOf(
                scan("STU001", "No Lates", "2026-09-21"),
                scan("STU002", "One Late", "2026-09-21", late = true),
            ),
        )
        assertEquals("STU002", totals.students.first().code)
    }

    @Test
    fun `days with activity counts distinct scan dates regardless of outcome`() {
        val totals = GateSummary.build(
            listOf(
                scan("STU001", "Ayham Yusop", "2026-09-21"),
                scan("STU002", "Other Student", "2026-09-21", outcome = OUTCOME_REJECTED),
                scan("STU001", "Ayham Yusop", "2026-09-22"),
            ),
        )
        assertEquals(2, totals.daysWithActivity)
    }
}
