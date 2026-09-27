package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * The school's "not arrived" alert, kept on the server (it sends the texts,
 * after each gate phone's report - see GateAbsenceService in the Laravel
 * patch): after [cutoffTime] on a school day, students with a card and no
 * gate scan are listed on the web and, with [textParents], their parents get
 * one text. Edited on Admin > Gate Schedule.
 */
class GateAbsenceSettingsRequest

data class GateAbsenceSettingsUpdateRequest(
    /** "HH:mm"; null turns the alert off. */
    @SerializedName("cutoff_time") val cutoffTime: String?,
    @SerializedName("text_parents") val textParents: Boolean,
    /** ISO weekdays with classes, 1 = Monday ... 7 = Sunday. */
    @SerializedName("school_days") val schoolDays: List<Int>,
)

data class GateAbsenceSettingsData(
    @SerializedName("enabled") val enabled: Boolean?,
    @SerializedName("cutoff_time") val cutoffTime: String?,
    @SerializedName("text_parents") val textParents: Boolean?,
    @SerializedName("school_days") val schoolDays: List<Int>?,
)
