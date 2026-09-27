package com.muslimedu.attendance.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.muslimedu.attendance.data.remote.ApiEnvelopeTypeAdapterFactory
import com.muslimedu.attendance.data.remote.dto.ApiEnvelope
import com.muslimedu.attendance.data.remote.dto.LoginData
import com.muslimedu.attendance.data.remote.dto.MeData
import com.muslimedu.attendance.data.remote.dto.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The web decides who may use the gate: the superadmin's per-school
 * `rfidGate` switch and, for a co-admin, the primary admin's `gateApp`
 * switch. Bodies below follow ApiController::buildUserPayload.
 */
class GateAccessTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(ApiEnvelopeTypeAdapterFactory())
        .create()

    private fun user(extra: String): UserDto {
        val body = """{"user":{"id":5,"name":"Aisha","email":"a@s.com","role":"admin","school_id":3$extra}}"""
        val envelope: ApiEnvelope<MeData> = gson.fromJson(body, object : TypeToken<ApiEnvelope<MeData>>() {}.type)
        return envelope.data!!.user!!
    }

    @Test
    fun `an older server that sends none of the keys is allowed`() {
        assertNull(GateAccess.problem(user("")))
    }

    @Test
    fun `primary admin of a school with the gate on is allowed`() {
        val u = user(""","is_primary_admin":true,"school_features":{"attendance":true,"rfidGate":true},"role_features":{"gateApp":false}""")
        assertNull(GateAccess.problem(u))
    }

    @Test
    fun `school with the gate switched off is refused, primary admin included`() {
        val u = user(""","is_primary_admin":true,"school_features":{"attendance":true,"rfidGate":false},"role_features":{"gateApp":true}""")
        assertEquals(GateAccess.SCHOOL_OFF_MESSAGE, GateAccess.problem(u))
    }

    @Test
    fun `co-admin without gate phone access is refused`() {
        val u = user(""","is_primary_admin":false,"school_features":{"rfidGate":true},"role_features":{"gateStudents":true,"gateApp":false}""")
        assertEquals(GateAccess.CO_ADMIN_OFF_MESSAGE, GateAccess.problem(u))
    }

    @Test
    fun `co-admin with gate phone access is allowed`() {
        val u = user(""","is_primary_admin":false,"school_features":{"rfidGate":true},"role_features":{"gateApp":true}""")
        assertNull(GateAccess.problem(u))
    }

    @Test
    fun `co-admin on a server without the gateApp key is allowed`() {
        val u = user(""","is_primary_admin":false,"school_features":{"attendance":true},"role_features":{"attendance":true}""")
        assertNull(GateAccess.problem(u))
    }

    @Test
    fun `PHP's empty array for role_features parses and counts as allowed`() {
        val u = user(""","is_primary_admin":false,"school_features":{"rfidGate":true},"role_features":[]""")
        assertNull(GateAccess.problem(u))
    }

    @Test
    fun `0 and 1 are read as off and on`() {
        assertNotNull(GateAccess.problem(user(""","school_features":{"rfidGate":0}""")))
        assertNull(GateAccess.problem(user(""","school_features":{"rfidGate":1}""")))
    }

    @Test
    fun `login body carries the same fields`() {
        val body = """{"success":true,"token":"tok","user":{"id":5,"name":"Aisha","email":"a@s.com","role":"admin","school_id":3,""" +
            """"is_primary_admin":false,"school_features":{"rfidGate":true},"role_features":{"gateApp":false}}}"""
        val envelope: ApiEnvelope<LoginData> = gson.fromJson(body, object : TypeToken<ApiEnvelope<LoginData>>() {}.type)
        assertEquals(GateAccess.CO_ADMIN_OFF_MESSAGE, GateAccess.problem(envelope.data!!.user!!))
    }

    @Test
    fun `the cached profile keeps the switches`() {
        // TokenManager caches the profile with a plain Gson.
        val plain = Gson()
        val u = user(""","is_primary_admin":false,"school_features":{"rfidGate":false},"role_features":[]""")
        val restored = plain.fromJson(plain.toJson(u), UserDto::class.java)
        assertEquals(GateAccess.SCHOOL_OFF_MESSAGE, GateAccess.problem(restored))
        assertEquals(false, restored.isPrimaryAdmin)
    }
}
