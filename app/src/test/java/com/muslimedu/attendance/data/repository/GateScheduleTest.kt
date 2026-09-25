package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.DIRECTION_IN
import com.muslimedu.attendance.data.db.entities.GateScanEntity.Companion.DIRECTION_OUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The admin's gate schedule: alternate Coming In / Going Out, up to N each per day. */
class GateScheduleTest {

    private var clock = 0L

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
        assertEquals(GateScanCheck.NotSetUp, GateSchedule.check(emptyList(), DIRECTION_IN, perDay = null))
    }

    @Test
    fun `morning only - one in and one out, then nothing more`() {
        val day = mutableListOf<GateScanEntity>()
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(day, DIRECTION_IN, 1))
        day += scan(DIRECTION_IN, "07:40")
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(day, DIRECTION_OUT, 1))
        day += scan(DIRECTION_OUT, "12:05")

        val again = GateSchedule.check(day, DIRECTION_IN, 1)
        assertTrue(again is GateScanCheck.LimitReached)
        assertEquals("07:40", (again as GateScanCheck.LimitReached).last.scanTime)
    }

    @Test
    fun `whole day - in, out, in, out, each numbered`() {
        val day = mutableListOf<GateScanEntity>()
        listOf(DIRECTION_IN to 1, DIRECTION_OUT to 1, DIRECTION_IN to 2, DIRECTION_OUT to 2).forEach { (direction, number) ->
            assertEquals(GateScanCheck.Allowed(number, perDay = 2), GateSchedule.check(day, direction, 2))
            day += scan(direction, "0$number:00")
        }
        assertTrue(GateSchedule.check(day, DIRECTION_IN, 2) is GateScanCheck.LimitReached)
        assertTrue(GateSchedule.check(day, DIRECTION_OUT, 2) is GateScanCheck.SameAsLast)
    }

    @Test
    fun `the same direction twice in a row is refused, naming the earlier scan`() {
        val first = scan(DIRECTION_IN, "07:42")
        val check = GateSchedule.check(listOf(first), DIRECTION_IN, 2)
        assertEquals(GateScanCheck.SameAsLast(first), check)
    }

    @Test
    fun `going out still works when the student forgot to scan in`() {
        assertEquals(GateScanCheck.Allowed(number = 1, perDay = 1), GateSchedule.check(emptyList(), DIRECTION_OUT, 1))
    }

    @Test
    fun `order of the list doesn't matter - the latest scan decides`() {
        val inScan = scan(DIRECTION_IN, "07:40")
        val outScan = scan(DIRECTION_OUT, "12:05")
        assertEquals(GateScanCheck.Allowed(number = 2, perDay = 2), GateSchedule.check(listOf(outScan, inScan), DIRECTION_IN, 2))
    }
}
