package com.muslimedu.attendance.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanRequest
import com.muslimedu.attendance.data.remote.dto.GateStudentsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the *proposed* offline gate contract (CLAUDE.md "Offline gate-only
 * mode") - not yet confirmed against real backend source like
 * [RealBackendResponseShapeTest]. Update these alongside the Laravel
 * implementation if it lands with a different shape.
 */
class GateOfflineContractTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(ApiEnvelopeTypeAdapterFactory())
        .create()

    @Test
    fun `gate scan upload sends the real scan date and time`() {
        val json = JsonParser.parseString(
            gson.toJson(GateAttendanceScanRequest(code = "S1001", direction = "in", date = "2026-09-23", time = "07:30")),
        ).asJsonObject

        assertEquals("S1001", json["code"].asString)
        assertEquals("in", json["direction"].asString)
        assertEquals("2026-09-23", json["date"].asString)
        assertEquals("07:30", json["time"].asString)
    }

    @Test
    fun `admin_gate_students parses a top-level students list and tolerates missing fields`() {
        val body = """
        {"students":[
          {"student_id":501,"name":"Arjun S","code":"S1001","photo":"https://x/p.jpg","gender":"male","section_id":10,"section_name":"Grade 8B"},
          {"student_id":502,"student_name":"Benny T","code":"S1002"}
        ]}
        """.trimIndent()
        val type = object : TypeToken<ApiEnvelope<GateStudentsData>>() {}.type
        val envelope: ApiEnvelope<GateStudentsData> = gson.fromJson(body, type)

        val students = envelope.data?.students.orEmpty()
        assertEquals(2, students.size)
        assertEquals("S1001", students[0].code)
        assertEquals("Grade 8B", students[0].sectionName)
        assertEquals("Benny T", students[1].name)
        assertNull(students[1].sectionId)
    }
}
