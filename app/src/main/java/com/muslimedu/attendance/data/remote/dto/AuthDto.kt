package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("device_name") val deviceName: String = "rfid-attendance-android",
)

data class LoginData(
    // Nullable because Gson will happily leave a field unset regardless of what
    // Kotlin declares; AuthRepository fails the login explicitly instead of
    // handing a null user to the UI and crashing there.
    @SerializedName("user") val user: UserDto?,
    @SerializedName(value = "token", alternate = ["access_token", "api_token"]) val token: String?,
    // Present instead of `token` when the account has 2FA enabled; this app
    // doesn't have an OTP entry screen yet, so AuthRepository surfaces this as
    // a distinct failure rather than silently treating it as a normal login.
    @SerializedName("requires_two_factor") val requiresTwoFactor: Boolean = false,
)

/**
 * Matches the real backend's buildUserPayload() (app/Http/Controllers/ApiController.php) -
 * confirmed against the actual PHP source, not the spec document. That payload
 * carries many more fields (permissions, school_features, role_features, the
 * orphan-profile block, etc.) this app has no use for; only the ones actually
 * read anywhere are declared here. There is no `school_name` field on the
 * backend at all (only `school_code`) - a prior version of this DTO declared
 * one anyway, which silently parsed to null forever since nothing here sends
 * that key.
 */
data class UserDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("role") val role: String,
    @SerializedName("school_id") val schoolId: Int,
    @SerializedName("code") val code: String?,
    @SerializedName("photo") val photo: String?,
)

/**
 * `/me`'s entire response is `{"user": {...}}` - a single wrapper key, not the
 * user's fields directly at the top level (unlike `/login`, which really does
 * put `user`/`token` at the top level - see [LoginData]). Confirmed from
 * ApiController::me(). Getting this shape wrong doesn't throw: Gson happily
 * builds a [UserDto] with every field left at its default (id=0, name=null,
 * ...) from an object that has none of those keys, so a session-restore
 * silently "succeeded" into garbage instead of failing loudly.
 */
data class MeData(
    @SerializedName("user") val user: UserDto?,
)

data class RefreshTokenData(
    @SerializedName("token") val token: String,
)
