package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.repository.GateSchedule
import com.muslimedu.attendance.data.repository.GateScheduleConfig
import com.muslimedu.attendance.security.AuditLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Admin > Gate Schedule: how many Coming In / Going Out scans each student
 * makes per day, when each opens, and after what time a Coming In is Late.
 */
@HiltViewModel
class GateScheduleViewModel @Inject constructor(
    private val deviceSettings: DeviceSettings,
    private val auditLogger: AuditLogger,
) : ViewModel() {

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
    }
}
