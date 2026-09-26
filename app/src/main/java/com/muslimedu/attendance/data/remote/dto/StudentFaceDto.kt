package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Faces shared between the school's gate phones. Not photos: each angle's
 * model numbers, base64 of little-endian float32 ([com.muslimedu.attendance.face.FaceCodec]).
 * The server keeps them encrypted and only gives them to admins of the
 * same school.
 */
data class StudentFaceAngleDto(
    @SerializedName("pose") val pose: Int,
    @SerializedName("embedding") val embedding: String,
    @SerializedName("liveness_score") val livenessScore: Float? = null,
)

/** `/admin_student_face_set`: replaces the student's shared face; the same [version] again changes nothing. */
data class StudentFaceSetRequest(
    @SerializedName("code") val code: String,
    @SerializedName("model") val model: String,
    @SerializedName("version") val version: String,
    @SerializedName("device_uid") val deviceUid: String?,
    @SerializedName("templates") val templates: List<StudentFaceAngleDto>,
)

data class StudentFaceSetData(
    @SerializedName("version") val version: String?,
)

/** `/admin_student_faces`: faces changed after [since] (the last download's server time), or all. */
data class StudentFacesRequest(
    @SerializedName("since") val since: String? = null,
)

data class StudentFacesData(
    @SerializedName("faces") val faces: List<StudentFaceDto>?,
    @SerializedName("server_time") val serverTime: String?,
)

data class StudentFaceDto(
    @SerializedName("code") val code: String?,
    @SerializedName("student_id") val studentId: Int?,
    @SerializedName("model") val model: String?,
    @SerializedName("version") val version: String?,
    @SerializedName("templates") val templates: List<StudentFaceAngleDto>?,
)
