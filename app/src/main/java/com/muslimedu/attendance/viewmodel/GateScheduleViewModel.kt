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
import javax.inject.Inject

/** Admin > Gate Schedule: how many Coming In / Going Out scans each student makes per day, and when each opens. */
@HiltViewModel
class GateScheduleViewModel @Inject constructor(
    private val deviceSettings: DeviceSettings,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    /** Null until set for the first time. */
    val schedule: StateFlow<GateScheduleConfig?> = deviceSettings.gateSchedule

    /** False when the times aren't in order (In 1 < Out 1 < In 2 ...) - nothing is saved then. */
    fun save(perDay: Int, inTimes: List<LocalTime>, outTimes: List<LocalTime>): Boolean {
        if (perDay !in GateSchedule.MIN_PER_DAY..GateSchedule.MAX_PER_DAY) return false
        if (inTimes.size != perDay || !GateSchedule.timesInOrder(inTimes, outTimes)) return false
        val config = GateScheduleConfig(perDay, inTimes, outTimes)
        val before = deviceSettings.gateSchedule.value
        deviceSettings.setGateSchedule(config)
        if (before != config) {
            viewModelScope.launch {
                auditLogger.log(action = AuditLogger.ACTION_GATE_SCHEDULE_SET, details = describe(config) + (before?.let { " (was ${describe(it)})" } ?: ""))
            }
        }
        return true
    }

    private fun describe(c: GateScheduleConfig) =
        "${c.perDay} In + ${c.perDay} Out; " + c.inTimes.indices.joinToString(", ") { "In ${c.inTimes[it]} / Out ${c.outTimes[it]}" }
}
