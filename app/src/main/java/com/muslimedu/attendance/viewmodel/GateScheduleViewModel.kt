package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.repository.GateSchedule
import com.muslimedu.attendance.security.AuditLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Admin > Gate Schedule: how many Coming In / Going Out scans each student makes per day. */
@HiltViewModel
class GateScheduleViewModel @Inject constructor(
    private val deviceSettings: DeviceSettings,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    /** Null until set for the first time. */
    val scansPerDay: StateFlow<Int?> = deviceSettings.gateScansPerDay

    fun save(perDay: Int) {
        val value = perDay.coerceIn(GateSchedule.MIN_PER_DAY, GateSchedule.MAX_PER_DAY)
        val before = deviceSettings.gateScansPerDay.value
        deviceSettings.setGateScansPerDay(value)
        if (before != value) {
            viewModelScope.launch {
                auditLogger.log(
                    action = AuditLogger.ACTION_GATE_SCHEDULE_SET,
                    details = "$value Coming In + $value Going Out per day" + (before?.let { " (was $it + $it)" } ?: ""),
                )
            }
        }
    }
}
