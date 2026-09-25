package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.DIRECTION_IN
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.DIRECTION_OUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** The admin's gate schedule: alternate Coming In / Going Out, up to N each per day, each from its opening time. */
class GateScheduleTest {

    private var clock = 0L

    private fun t(hhmm: String): LocalTime = LocalTime.parse(hhmm)

    private val morningOnly = GateScheduleConfig(1, listOf(t("06:00")), listOf(t("11:00")))
    private val wholeDay = GateScheduleConfig(2, listOf(t("06:00"), t("12:30")), listOf(t("11:30"), t("16:00")))

    /** One face-confirmed scan, each a minute after the previous. */
    private fun scan(direction: String, time: String) = GateScanEntity(
        schoolId = 1,
        studentCode = "STU20260004",
        studentName = "Ayham Yusop",
        direction = direction,
        scanDate = "2026-09-25",
        scanTime = time,
        scannedAt = (clock++) * 60_000L,
        verifiedByFace = true,
        faceMatchScore = 0.9f,
    )

    @Test
    fun `nothing works until the admin sets a schedule`() {
        assertEquals(GateScanCheck.NotSetUp, GateSchedule.check(emptyList(), DIRECTION_IN, null, t("07:00")))
    }

    @Test
    fun `morning only - one in and one out, then nothing more`() {
        val day = mutableListOf<GateScanEntity>()
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(day, DIRECTION_IN, morningOnly, t("07:40")))
        day += scan(DIRECTION_IN, "07:40")
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(day, DIRECTION_OUT, morningOnly, t("11:05")))
        day += scan(DIRECTION_OUT, "11:05")

        val again = GateSchedule.check(day, DIRECTION_IN, morningOnly, t("11:30"))
        assertTrue(again is GateScanCheck.LimitReached)
        assertEquals("07:40", (again as GateScanCheck.LimitReached).last.scanTime)
    }

    @Test
    fun `going out is not open before its time`() {
        val day = listOf(scan(DIRECTION_IN, "07:40"))
        assertEquals(GateScanCheck.NotOpenYet(number = 1, opensAt = t("11:00")), GateSchedule.check(day, DIRECTION_OUT, morningOnly, t("10:59")))
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(day, DIRECTION_OUT, morningOnly, t("11:00")))
    }

    @Test
    fun `whole day - each scan opens at its own time`() {
        val day = mutableListOf<GateScanEntity>()
        listOf(
            Triple(DIRECTION_IN, 1, "07:00"),
            Triple(DIRECTION_OUT, 1, "11:30"),
            Triple(DIRECTION_IN, 2, "12:30"),
            Triple(DIRECTION_OUT, 2, "16:00"),
        ).forEach { (direction, number, at) ->
            assertEquals(GateScanCheck.Allowed(number, perDay = 2), GateSchedule.check(day, direction, wholeDay, t(at)))
            day += scan(direction, at)
        }
        assertTrue(GateSchedule.check(day, DIRECTION_IN, wholeDay, t("17:00")) is GateScanCheck.LimitReached)
        assertTrue(GateSchedule.check(day, DIRECTION_OUT, wholeDay, t("17:00")) is GateScanCheck.SameAsLast)
    }

    @Test
    fun `back from lunch too early - the second coming in waits for its time`() {
        val day = listOf(scan(DIRECTION_IN, "07:00"), scan(DIRECTION_OUT, "11:35"))
        assertEquals(GateScanCheck.NotOpenYet(number = 2, opensAt = t("12:30")), GateSchedule.check(day, DIRECTION_IN, wholeDay, t("12:10")))
    }

    @Test
    fun `the same direction twice in a row is refused, naming the earlier scan`() {
        val first = scan(DIRECTION_IN, "07:42")
        assertEquals(GateScanCheck.SameAsLast(first), GateSchedule.check(listOf(first), DIRECTION_IN, wholeDay, t("07:43")))
    }

    @Test
    fun `going out still works when the student forgot to scan in`() {
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(emptyList(), DIRECTION_OUT, morningOnly, t("11:10")))
    }

    @Test
    fun `order of the list doesn't matter - the latest scan decides`() {
        val inScan = scan(DIRECTION_IN, "07:40")
        val outScan = scan(DIRECTION_OUT, "12:05")
        assertEquals(GateScanCheck.Allowed(number = 2, perDay = 2), GateSchedule.check(listOf(outScan, inScan), DIRECTION_IN, wholeDay, t("13:00")))
    }

    @Test
    fun `default times are in order for every count`() {
        for (perDay in GateSchedule.MIN_PER_DAY..GateSchedule.MAX_PER_DAY) {
            val (inTimes, outTimes) = GateSchedule.defaultTimes(perDay)
            assertEquals(perDay, inTimes.size)
            assertTrue("defaults for $perDay: $inTimes / $outTimes", GateSchedule.timesInOrder(inTimes, outTimes))
        }
        assertEquals(morningOnly.inTimes to morningOnly.outTimes, GateSchedule.defaultTimes(1))
        assertEquals(wholeDay.inTimes to wholeDay.outTimes, GateSchedule.defaultTimes(2))
    }

    @Test
    fun `times out of order are rejected`() {
        assertFalse(GateSchedule.timesInOrder(listOf(t("11:00")), listOf(t("06:00"))))
        assertFalse(GateSchedule.timesInOrder(listOf(t("06:00"), t("11:00")), listOf(t("12:00"), t("16:00"))))
        assertFalse(GateSchedule.timesInOrder(listOf(t("06:00")), listOf(t("06:00"))))
        assertTrue(GateSchedule.timesInOrder(wholeDay.inTimes, wholeDay.outTimes))
    }
}
