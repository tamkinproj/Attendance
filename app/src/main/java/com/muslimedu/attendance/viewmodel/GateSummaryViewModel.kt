package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.repository.GateAttendanceRepository
import com.muslimedu.attendance.data.repository.GateSummary
import com.muslimedu.attendance.data.repository.GateSummaryPeriod
import com.muslimedu.attendance.data.repository.GateSummaryRange
import com.muslimedu.attendance.data.repository.GateSummaryTotals
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

/**
 * Drives the Attendance Summary screen: a week or month at a time, stepped
 * with prev/next (capped at today, like the web's Gate Reports), aggregated
 * from this device's own `gate_scans` - see [GateSummary]'s own doc comment
 * for exactly what that does and doesn't cover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GateSummaryViewModel @Inject constructor(
    private val gateAttendanceRepository: GateAttendanceRepository,
) : ViewModel() {

    private val _period = MutableStateFlow(GateSummaryPeriod.Week)
    val period: StateFlow<GateSummaryPeriod> = _period.asStateFlow()

    private val _anchor = MutableStateFlow(LocalDate.now())

    val range: StateFlow<GateSummaryRange> = combine(_period, _anchor) { period, anchor ->
        GateSummary.periodRange(period, anchor)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GateSummary.periodRange(_period.value, _anchor.value),
    )

    val canStepForward: StateFlow<Boolean> = combine(_period, _anchor) { period, anchor ->
        GateSummary.canStepForward(period, anchor)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GateSummary.canStepForward(_period.value, _anchor.value),
    )

    val summary: StateFlow<GateSummaryTotals> = range
        .flatMapLatest { r -> gateAttendanceRepository.observeForDateRange(r.start.toString(), r.end.toString()) }
        .map { scans -> GateSummary.build(scans) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateSummary.build(emptyList()))

    fun selectPeriod(newPeriod: GateSummaryPeriod) {
        if (newPeriod == _period.value) return
        _period.value = newPeriod
        _anchor.value = LocalDate.now()
    }

    fun stepBack() {
        _anchor.value = GateSummary.shiftAnchor(_period.value, _anchor.value, forward = false)
    }

    fun stepForward() {
        if (canStepForward.value) _anchor.value = GateSummary.shiftAnchor(_period.value, _anchor.value, forward = true)
    }

    fun goToToday() {
        _anchor.value = LocalDate.now()
    }
}
