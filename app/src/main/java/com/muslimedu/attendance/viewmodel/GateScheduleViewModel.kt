package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateAbsenceSettingsData
import com.muslimedu.attendance.data.remote.dto.GateAbsenceSettingsRequest
import com.muslimedu.attendance.data.remote.dto.GateAbsenceSettingsUpdateRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.data.repository.GateSchedule
import com.muslimedu.attendance.data.repository.GateScheduleConfig
import com.muslimedu.attendance.data.repository.isMissingEndpoint
import com.muslimedu.attendance.security.AuditLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** The "not arrived" alert as the server has it. */
data class AbsenceSettings(
    val enabled: Boolean,
    val cutoff: LocalTime?,
    val textParents: Boolean,
    /** ISO weekdays, 1 = Monday ... 7 = Sunday. */
    val schoolDays: Set<Int>,
)

sealed class AbsenceSettingsState {
    data object Loading : AbsenceSettingsState()
    data class Loaded(val settings: AbsenceSettings) : AbsenceSettingsState()

    /** Can't be shown or changed right now - [message] says why (offline, server not updated). */
    data class Unavailable(val message: String) : AbsenceSettingsState()
}

/**
 * Admin > Gate Schedule: how many Coming In / Going Out scans each student
 * makes per day, when each opens, and after what time a Coming In is Late -
 * all kept on this phone - plus the school's "not arrived" alert, which is
 * kept on the school server because the server sends those texts.
 */
@HiltViewModel
class GateScheduleViewModel @Inject constructor(
    private val deviceSettings: DeviceSettings,
    private val auditLogger: AuditLogger,
    private val apiService: ApiService,
) : ViewModel() {

    private val _absence = MutableStateFlow<AbsenceSettingsState>(AbsenceSettingsState.Loading)
    val absence: StateFlow<AbsenceSettingsState> = _absence.asStateFlow()

    fun loadAbsence() {
        viewModelScope.launch {
            _absence.value = AbsenceSettingsState.Loading
            _absence.value = absenceCall { apiService.adminGateAbsenceSettings(GateAbsenceSettingsRequest()) }.fold(
                onSuccess = { AbsenceSettingsState.Loaded(it) },
                onFailure = { AbsenceSettingsState.Unavailable(it.message ?: "Couldn't load") },
            )
        }
    }

    /**
     * Saves the alert on the server; null when saved (or nothing changed),
     * else why not. Checked against the schedule first (see
     * [GateSchedule.absenceCutoffProblem]).
     */
    suspend fun saveAbsence(settings: AbsenceSettings, schedule: GateScheduleConfig): String? {
        val current = (_absence.value as? AbsenceSettingsState.Loaded)?.settings ?: return null
        if (current == settings) return null
        if (settings.enabled) {
            val cutoff = settings.cutoff ?: return "Choose the not-arrived time."
            GateSchedule.absenceCutoffProblem(cutoff, schedule.inTimes, schedule.outTimes, schedule.lateAfter)?.let { return it }
            if (settings.schoolDays.isEmpty()) return "Choose at least one school day."
        }
        val request = GateAbsenceSettingsUpdateRequest(
            cutoffTime = settings.cutoff?.takeIf { settings.enabled }?.format(HH_MM),
            textParents = settings.textParents,
            schoolDays = settings.schoolDays.sorted().ifEmpty { listOf(1, 2, 3, 4, 5) },
        )
        return absenceCall { apiService.adminGateAbsenceSettingsUpdate(request) }.fold(
            onSuccess = { saved ->
                _absence.value = AbsenceSettingsState.Loaded(saved)
                auditLogger.log(
                    action = AuditLogger.ACTION_GATE_SCHEDULE_SET,
                    details = "not-arrived alert: " + if (saved.enabled) {
                        "after ${saved.cutoff}, texts ${if (saved.textParents) "on" else "off"}, days ${saved.schoolDays.sorted()}"
                    } else {
                        "off"
                    },
                )
                null
            },
            onFailure = { "The not-arrived alert wasn't saved: ${it.message}" },
        )
    }

    private suspend fun absenceCall(block: suspend () -> com.muslimedu.attendance.data.remote.dto.ApiEnvelope<GateAbsenceSettingsData>): Result<AbsenceSettings> =
        try {
            val response = block()
            val data = response.data
            if (response.success && data != null) Result.success(data.toSettings()) else Result.failure(Exception(response.message ?: "The server refused it"))
        } catch (e: HttpException) {
            Result.failure(
                Exception(
                    when {
                        isMissingEndpoint(e.code()) -> "The school server doesn't have the not-arrived alert yet - upload the latest server patch."
                        e.code() == 401 -> "Session expired - sign out and sign in again in Sync & Account."
                        else -> e.extractApiErrorMessage() ?: "Server error (${e.code()})"
                    },
                ),
            )
        } catch (e: IOException) {
            Result.failure(Exception("No connection - the not-arrived alert is kept on the school server; connect to the internet to change it."))
        }

    private fun GateAbsenceSettingsData.toSettings() = AbsenceSettings(
        enabled = enabled == true && cutoffTime != null,
        cutoff = cutoffTime?.let { runCatching { LocalTime.parse(it.take(5), HH_MM) }.getOrNull() },
        textParents = textParents == true,
        schoolDays = schoolDays?.filter { it in 1..7 }?.toSet()?.ifEmpty { null } ?: DEFAULT_SCHOOL_DAYS,
    )

    /** Null until set for the first time. */
    val schedule: StateFlow<GateScheduleConfig?> = deviceSettings.gateSchedule

    /**
     * Saves and returns null, or returns why nothing was saved: the opening
     * times out of order (In 1 < Out 1 < In 2 ...), or a late time outside
     * its Coming In (from its opening, before the Going Out after it).
     */
    fun save(perDay: Int, inTimes: List<LocalTime>, outTimes: List<LocalTime>, lateAfter: List<LocalTime?>): String? {
        if (perDay !in GateSchedule.MIN_PER_DAY..GateSchedule.MAX_PER_DAY || inTimes.size != perDay) return "Choose 1 to 4 scans each way."
        if (!GateSchedule.timesInOrder(inTimes, outTimes)) {
            return "Each opening time must be later than the one before it (Coming In 1, Going Out 1, Coming In 2...)."
        }
        if (!GateSchedule.lateAfterValid(inTimes, outTimes, lateAfter)) {
            val i = lateAfter.indices.first { index -> lateAfter[index]?.let { it < inTimes[index] || it >= outTimes[index] } == true }
            return "Late after for Coming In ${i + 1} must be from its opening (${inTimes[i].format(H_MM_A)}) " +
                "and before Going Out ${i + 1} (${outTimes[i].format(H_MM_A)})."
        }
        val config = GateScheduleConfig(perDay, inTimes, outTimes, lateAfter)
        val before = deviceSettings.gateSchedule.value
        deviceSettings.setGateSchedule(config)
        if (before != config) {
            viewModelScope.launch {
                auditLogger.log(action = AuditLogger.ACTION_GATE_SCHEDULE_SET, details = describe(config) + (before?.let { " (was ${describe(it)})" } ?: ""))
            }
        }
        return null
    }

    private fun describe(c: GateScheduleConfig) =
        "${c.perDay} In + ${c.perDay} Out; " + c.inTimes.indices.joinToString(", ") {
            "In ${c.inTimes[it]} (late after ${c.lateAfterFor(it + 1) ?: "none"}) / Out ${c.outTimes[it]}"
        }

    private companion object {
        val H_MM_A: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DEFAULT_SCHOOL_DAYS = setOf(1, 2, 3, 4, 5)
    }
}
