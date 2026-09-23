package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.AttendanceEntity
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.sync.SyncQueueManager
import com.muslimedu.attendance.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SyncStatusSummary(
    val syncedToday: Int,
    val pendingToday: Int,
    val failedToday: Int,
)

/** One failed row, with the student's name resolved for display where the local cache still has it. */
data class FailedRecord(
    val studentName: String,
    val checkInTime: String,
    val errorMessage: String,
    val syncAttempts: Int,
)

@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val attendanceDao: AttendanceDao,
    private val studentDao: StudentDao,
    private val syncQueueManager: SyncQueueManager,
    private val sessionManager: SessionManager,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _summary = MutableStateFlow<SyncStatusSummary?>(null)
    val summary: StateFlow<SyncStatusSummary?> = _summary.asStateFlow()

    private val _failedRecords = MutableStateFlow<List<FailedRecord>>(emptyList())
    val failedRecords: StateFlow<List<FailedRecord>> = _failedRecords.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    init {
        // Reloads on every session change - see TeacherDashboardViewModel's
        // init for why a one-shot load showed the previous account's numbers.
        viewModelScope.launch {
            sessionManager.currentUser.collect { user ->
                if (user == null) {
                    _summary.value = null
                    _failedRecords.value = emptyList()
                } else {
                    load(user.schoolId)
                }
            }
        }
    }

    fun refresh() {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        viewModelScope.launch { load(schoolId) }
    }

    /** Resets FAILED rows to PENDING then flushes immediately - same pattern as AdminDashboardViewModel's retry. */
    fun retryFailedSyncs() {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        viewModelScope.launch {
            _isBusy.value = true
            attendanceDao.resetFailedToPending()
            syncQueueManager.flush()
            load(schoolId)
            _isBusy.value = false
        }
    }

    fun syncNow() {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        viewModelScope.launch {
            _isBusy.value = true
            syncQueueManager.flush()
            load(schoolId)
            _isBusy.value = false
        }
    }

    private suspend fun load(schoolId: Int) {
        val today = LocalDate.now().toString()
        _summary.value = SyncStatusSummary(
            syncedToday = attendanceDao.countByStatusOnDate(schoolId, today, AttendanceEntity.SYNC_SYNCED),
            pendingToday = attendanceDao.countByStatusOnDate(schoolId, today, AttendanceEntity.SYNC_PENDING),
            failedToday = attendanceDao.countByStatusOnDate(schoolId, today, AttendanceEntity.SYNC_FAILED),
        )
        _failedRecords.value = attendanceDao.getFailedOnDate(schoolId, today).map { record ->
            val student = studentDao.findBySchoolAndStudentId(record.schoolId, record.studentId)
            FailedRecord(
                studentName = student?.name ?: "Student #${record.studentId}",
                checkInTime = record.checkInTime,
                errorMessage = record.errorMessage ?: "Unknown error",
                syncAttempts = record.syncAttempts,
            )
        }
    }
}
