package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.GateScheduleConfig
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.sync.GateSyncManager
import com.muslimedu.attendance.sync.GateSyncOutcome
import com.muslimedu.attendance.util.DeviceHealth
import com.muslimedu.attendance.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/** Today's face-confirmed attendance at this gate, by each student's latest direction. [late]: late Coming Ins. */
data class GateTodayStats(val onCampus: Int = 0, val left: Int = 0, val recorded: Int = 0, val failed: Int = 0, val late: Int = 0)

/**
 * The gate's home: choose Coming In or Going Out, see what's been recorded
 * and whether it has reached the web admin. Scanning itself happens on the
 * dedicated screen ([GateScanViewModel]).
 */
@HiltViewModel
class GateDashboardViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
    private val gateSyncManager: GateSyncManager,
    rfidManager: RfidManager,
    networkMonitor: NetworkMonitor,
    deviceSettings: DeviceSettings,
) : ViewModel() {

    /** The admin's gate schedule; null until set - the gate is blocked until then. */
    val schedule: StateFlow<GateScheduleConfig?> = deviceSettings.gateSchedule

    /** The time of day, refreshed every 20s, so a direction unlocks on the dashboard at its opening time by itself. */
    val now: StateFlow<LocalTime> = flow {
        while (true) {
            emit(LocalTime.now())
            delay(20_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalTime.now())

    val today: StateFlow<GateTodayStats> = gateAttendanceRepository.observeToday()
        .map(::toStats)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateTodayStats())

    val recent: StateFlow<List<GateScanEntity>> = gateAttendanceRepository.observeRecent(RECENT_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unsyncedCount: StateFlow<Int> = gateAttendanceRepository.observeUnsyncedAttendanceCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val failedUploadCount: StateFlow<Int> = gateAttendanceRepository.observeCount(GateScanEntity.SYNC_FAILED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lastSyncedAt: StateFlow<Long?> = gateAttendanceRepository.observeLastSyncedAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "This phone's clock is 12 min fast" when the server's last device-health
     * check found it off - scans are stamped with this clock.
     */
    val clockWarning: StateFlow<String?> = deviceSettings.deviceHealth
        .map { DeviceHealth.clockWarning(it?.clockSkewSeconds) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isSyncing: StateFlow<Boolean> = gateSyncManager.isSyncing
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val readerStatus = rfidManager.status

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    init {
        rfidManager.register()
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncMessage.value = when (val outcome = gateSyncManager.flush()) {
                GateSyncOutcome.NotSignedIn -> "Sign in as a school admin to sync."
                is GateSyncOutcome.Finished -> when {
                    outcome.stoppedReason != null -> outcome.stoppedReason
                    outcome.uploaded == 0 -> "Everything is already synced."
                    else -> "Synced ${outcome.uploaded} record(s)."
                }
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    private fun toStats(scans: List<GateScanEntity>): GateTodayStats {
        val recorded = scans.filter { it.outcome == GateScanEntity.OUTCOME_RECORDED }
        // Newest first, so the first record per student is where they are now.
        val latestByStudent = recorded.groupBy { it.studentCode }.mapValues { it.value.first().direction }
        return GateTodayStats(
            onCampus = latestByStudent.count { it.value == GateScanEntity.DIRECTION_IN },
            left = latestByStudent.count { it.value == GateScanEntity.DIRECTION_OUT },
            recorded = recorded.size,
            failed = scans.size - recorded.size,
            late = recorded.count { it.late },
        )
    }

    companion object {
        private const val RECENT_LIMIT = 15
    }
}
