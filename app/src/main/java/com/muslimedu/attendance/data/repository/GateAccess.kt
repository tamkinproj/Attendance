package com.muslimedu.attendance.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.muslimedu.attendance.data.remote.dto.UserDto

/**
 * Whether an admin account may use this app, as decided on the web: the
 * superadmin's per-school "RFID Gate Attendance" switch (`rfidGate` in
 * `school_features`) and, for a co-admin, the primary admin's Dashboard
 * Cards > Co-Admins > "Gate phones" switch (`gateApp` in `role_features`).
 * The server enforces the same two rules on every gate endpoint
 * (EnsureGateAccess); checking them at sign-in just says why up front
 * instead of letting every upload come back 403.
 *
 * A key the server doesn't send (an older server) counts as allowed.
 */
object GateAccess {
    const val SCHOOL_FEATURE = "rfidGate"
    const val CO_ADMIN_FEATURE = "gateApp"

    const val SCHOOL_OFF_MESSAGE =
        "RFID gate attendance is turned off for your school. Ask the platform administrator to turn it on."
    const val CO_ADMIN_OFF_MESSAGE =
        "Your school's main admin hasn't given co-admins access to the gate phones. " +
            "They can turn on \"Gate phones\" in Dashboard Cards > Co-Admins, or sign in with the main admin account."

    /** Why [user] can't use the gate, or null when they can. */
    fun problem(user: UserDto): String? = when {
        user.schoolFeatures.flag(SCHOOL_FEATURE) == false -> SCHOOL_OFF_MESSAGE
        user.isPrimaryAdmin != true && user.roleFeatures.flag(CO_ADMIN_FEATURE) == false -> CO_ADMIN_OFF_MESSAGE
        else -> null
    }

    private fun JsonElement?.flag(key: String): Boolean? {
        val value = (this as? JsonObject)?.get(key) ?: return null
        if (!value.isJsonPrimitive) return null
        val primitive = value.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asInt != 0
            else -> null
        }
    }
}
