package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.sync.GateSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

enum class GateHistoryFilter(val label: String) {
    All("All"),
    In("Coming In"),
    Out("Going Out"),
    Failed("Face failed"),
}

/** This gate's RFID scan history, one day at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GateHistoryViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
    gateSyncManager: GateSyncManager,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now().toString())
    val date: StateFlow<String> = _date.asStateFlow()

    private val _filter = MutableStateFlow(GateHistoryFilter.All)
    val filter: StateFlow<GateHistoryFilter> = _filter.asStateFlow()

    /** Days with records, newest first - today is always offered. */
    val dates: StateFlow<List<String>> = gateAttendanceRepository.observeDates()
        .map { days -> (listOf(LocalDate.now().toString()) + days).distinct().sortedDescending() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(LocalDate.now().toString()))

    val records: StateFlow<List<GateScanEntity>> = _date
        .flatMapLatest { gateAttendanceRepository.observeForDate(it) }
        .combine(_filter) { scans, filter ->
            when (filter) {
                GateHistoryFilter.All -> scans
                GateHistoryFilter.In -> scans.filter { it.outcome == GateScanEntity.OUTCOME_RECORDED && it.direction == GateScanEntity.DIRECTION_IN }
                GateHistoryFilter.Out -> scans.filter { it.outcome == GateScanEntity.OUTCOME_RECORDED && it.direction == GateScanEntity.DIRECTION_OUT }
                GateHistoryFilter.Failed -> scans.filter { it.outcome == GateScanEntity.OUTCOME_REJECTED }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isSyncing: StateFlow<Boolean> = gateSyncManager.isSyncing

    fun selectDate(date: String) {
        _date.value = date
    }

    fun selectFilter(filter: GateHistoryFilter) {
        _filter.value = filter
    }
}
