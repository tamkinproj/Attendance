package com.muslimedu.attendance.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.remote.dto.LoginData
import com.muslimedu.attendance.data.remote.dto.MeData
import com.muslimedu.attendance.data.remote.dto.TeacherClassesData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug where a successful login was reported to the
 * user as the error "Login successful": the server said yes, the envelope
 * parsed to `success = false` / `data = null`, and AuthRepository surfaced the
 * server's own success message as a failure.
 */
class ApiEnvelopeParsingTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(ApiEnvelopeTypeAdapterFactory())
        .create()

    private val user = """{"id":7,"name":"Qahtan","email":"q@furqan.com","role":"teacher","school_id":3}"""

    private fun login(body: String): ApiEnvelope<LoginData>? =
        gson.fromJson(body, object : TypeToken<ApiEnvelope<LoginData>>() {}.type)

    @Test
    fun `payload nested under data`() {
        val envelope = login("""{"success":true,"message":"Login successful","data":{"token":"tok","user":$user}}""")!!
        assertTrue(envelope.success)
        assertEquals("tok", envelope.data?.token)
        assertEquals("Qahtan", envelope.data?.user?.name)
    }

    @Test
    fun `payload at the top level with no data key`() {
        val envelope = login("""{"success":true,"message":"Login successful","token":"tok","user":$user}""")!!
        assertTrue(envelope.success)
        assertEquals("tok", envelope.data?.token)
        assertEquals("Qahtan", envelope.data?.user?.name)
    }

    @Test
    fun `a 2xx body with no success flag is a success`() {
        val envelope = login("""{"message":"Login successful","data":{"token":"tok","user":$user}}""")!!
        assertTrue(envelope.success)
        assertEquals("tok", envelope.data?.token)
    }

    @Test
    fun `status success is read as success`() {
        val envelope = login("""{"status":"success","message":"Login successful","token":"tok","user":$user}""")!!
        assertTrue(envelope.success)
        assertEquals("tok", envelope.data?.token)
    }

    @Test
    fun `sanctum access_token is read as the token`() {
        val envelope = login("""{"message":"Login successful","access_token":"tok","token_type":"Bearer","user":$user}""")!!
        assertEquals("tok", envelope.data?.token)
    }

    @Test
    fun `an explicit failure stays a failure and keeps the server message`() {
        val envelope = login("""{"success":false,"message":"Invalid credentials"}""")!!
        assertFalse(envelope.success)
        assertEquals("Invalid credentials", envelope.message)
    }

    @Test
    fun `status error stays a failure`() {
        val envelope = login("""{"status":"error","message":"These credentials do not match our records"}""")!!
        assertFalse(envelope.success)
    }

    @Test
    fun `the top-level fallback never rescues an explicit failure`() {
        val envelope = login("""{"success":false,"message":"Invalid credentials","token":"leaked"}""")!!
        assertFalse(envelope.success)
        assertNull(envelope.data)
    }

    @Test
    fun `a success with no payload keys leaves data null rather than inventing an empty one`() {
        val envelope = login("""{"success":true,"message":"Login successful"}""")!!
        assertTrue(envelope.success)
        assertNull(envelope.data)
    }

    @Test
    fun `an explicit null data stays null`() {
        val envelope = login("""{"success":true,"message":"Login successful","data":null}""")!!
        assertNull(envelope.data)
    }

    @Test
    fun `validation errors are preserved`() {
        val envelope = login("""{"success":false,"message":"Validation failed","errors":{"email":["The email field is required."]}}""")!!
        assertEquals("The email field is required.", envelope.errors?.get("email")?.first())
    }

    @Test
    fun `an unexpected errors shape is tolerated rather than fatal`() {
        val envelope = login("""{"success":false,"message":"Nope","errors":"not a map"}""")!!
        assertEquals("Nope", envelope.message)
        assertNull(envelope.errors)
    }

    @Test
    fun `a non-object body parses to null instead of throwing`() {
        assertNull(login("[1,2,3]"))
    }

    // The real shapes below (a "user" wrapper key on /me; a flat "classes"
    // list, no nested subject object) are confirmed against the actual
    // backend source - see RealBackendResponseShapeTest, which is the
    // authoritative coverage for those two endpoints specifically. This test
    // only re-uses them to prove the envelope's generic top-level-as-payload
    // fallback isn't special-cased to LoginData.
    @Test
    fun `other endpoints get the same tolerance`() {
        val meType = object : TypeToken<ApiEnvelope<MeData>>() {}.type
        val me: ApiEnvelope<MeData> = gson.fromJson(
            """{"user":{"id":7,"name":"Qahtan","email":"q@furqan.com","role":"admin","school_id":3}}""",
            meType,
        )
        assertTrue(me.success)
        assertEquals("admin", me.data?.user?.role)

        val classesType = object : TypeToken<ApiEnvelope<TeacherClassesData>>() {}.type
        val classes: ApiEnvelope<TeacherClassesData> = gson.fromJson(
            """{"classes":[{"section_id":1,"section_name":"Grade 5A","class_id":null,"class_name":null,"subject_id":9,"subject_name":"Quran","role":"subject"}]}""",
            classesType,
        )
        assertTrue(classes.success)
        assertEquals("Quran", classes.data?.classes?.first()?.subjectName)
    }
}
