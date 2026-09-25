package com.muslimedu.attendance.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.remote.dto.GateAttendanceScanRequest
import com.muslimedu.attendance.data.remote.dto.GateRejectedScanRequest
import com.muslimedu.attendance.data.remote.dto.GateStudentsData
import com.muslimedu.attendance.data.remote.dto.StudentRfidSetRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the gate contract the app and the Laravel patch share (CLAUDE.md
 * "RFID + face confirmation gate"). The server side is written to the same
 * shapes; update both together.
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

    @Test
    fun `verified attendance upload carries the card, the face result and an event id`() {
        val json = JsonParser.parseString(
            gson.toJson(
                GateAttendanceScanRequest(
                    code = "2026-00123", direction = "in", date = "2026-09-25", time = "07:42",
                    rfidUid = "04:A1:B2:C3", rfidVerified = true, faceConfirmed = true, faceScore = 0.91f,
                    deviceEventId = "3f2b-uuid",
                ),
            ),
        ).asJsonObject

        assertEquals("04:A1:B2:C3", json["rfid_uid"].asString)
        assertTrue(json["rfid_verified"].asBoolean)
        assertTrue(json["face_confirmed"].asBoolean)
        assertEquals(0.91f, json["face_score"].asFloat, 0.0001f)
        assertEquals("3f2b-uuid", json["device_event_id"].asString)
    }

    @Test
    fun `failed face check goes to its own endpoint with the reason`() {
        val json = JsonParser.parseString(
            gson.toJson(
                GateRejectedScanRequest(
                    code = "2026-00123", direction = "out", date = "2026-09-25", time = "15:10",
                    rfidUid = "04:A1:B2:C3", reason = "Face does not match", faceScore = 0.31f, deviceEventId = "e1",
                ),
            ),
        ).asJsonObject

        assertEquals("out", json["direction"].asString)
        assertEquals("Face does not match", json["reason"].asString)
        assertEquals("e1", json["device_event_id"].asString)
        assertFalse(json.has("face_confirmed"))
    }

    @Test
    fun `card registry request says assign or remove explicitly`() {
        val assign = JsonParser.parseString(
            gson.toJson(StudentRfidSetRequest("2026-00123", StudentRfidSetRequest.ACTION_ASSIGN, "04:A1:B2:C3")),
        ).asJsonObject
        assertEquals("assign", assign["action"].asString)
        assertEquals("04:A1:B2:C3", assign["rfid_uid"].asString)

        val remove = JsonParser.parseString(
            gson.toJson(StudentRfidSetRequest("2026-00123", StudentRfidSetRequest.ACTION_REMOVE)),
        ).asJsonObject
        assertEquals("remove", remove["action"].asString)
        assertFalse(remove.has("rfid_uid"))
    }

    @Test
    fun `student download carries cards only when the server manages them`() {
        val type = object : TypeToken<ApiEnvelope<GateStudentsData>>() {}.type
        val managed: ApiEnvelope<GateStudentsData> = gson.fromJson(
            """{"rfid_managed":true,"students":[{"student_id":7,"name":"Juan Dela Cruz","code":"2026-00123","rfid_uid":"04:A1:B2:C3"},""" +
                """{"student_id":8,"name":"No Card","code":"2026-00124","rfid_uid":null}]}""",
            type,
        )
        assertEquals(true, managed.data?.rfidManaged)
        assertEquals("04:A1:B2:C3", managed.data?.students?.get(0)?.rfidUid)
        assertNull(managed.data?.students?.get(1)?.rfidUid)

        // An older server: no flag, so the app must keep its own cards.
        val older: ApiEnvelope<GateStudentsData> = gson.fromJson("""{"students":[{"student_id":7,"code":"2026-00123"}]}""", type)
        assertNull(older.data?.rfidManaged)
    }

    @Test
    fun `only a card read plus a confirmed face counts as verified attendance`() {
        val base = GateScanEntity(
            schoolId = 1, studentCode = "2026-00123", studentName = "Juan", direction = "in",
            scanDate = "2026-09-25", scanTime = "07:42", scannedAt = 0L, verifiedByFace = true, faceMatchScore = 0.9f,
            rfidVerified = true, outcome = GateScanEntity.OUTCOME_RECORDED,
        )
        assertTrue(base.isVerifiedAttendance)
        assertFalse(base.copy(verifiedByFace = false).isVerifiedAttendance)
        assertFalse(base.copy(rfidVerified = false).isVerifiedAttendance)
        assertFalse(base.copy(outcome = GateScanEntity.OUTCOME_REJECTED).isVerifiedAttendance)
    }
}
