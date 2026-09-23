package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
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

data class AdminStats(
    val totalStudents: Int,
    val studentsWithFace: Int,
    val studentsWithRfid: Int,
    val syncedToday: Int,
    val pendingToday: Int,
    val failedToday: Int,
    /** The actual reason(s) behind [failedToday] - so a failure is visible and diagnosable, not just a count. */
    val failedMessages: List<String> = emptyList(),
)

@HiltViewModel
class AdminDashboardViewModel @Inject constructor(
    private val studentDao: StudentDao,
    private val faceTemplateDao: FaceTemplateDao,
    private val attendanceDao: AttendanceDao,
    private val syncQueueManager: SyncQueueManager,
    private val sessionManager: SessionManager,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _stats = MutableStateFlow<AdminStats?>(null)
    val stats: StateFlow<AdminStats?> = _stats.asStateFlow()

    /** Whether "Sync Roster from Server"/"Retry Failed Syncs" are likely to actually reach the network right now. */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    private val _isRetrying = MutableStateFlow(false)
    val isRetrying: StateFlow<Boolean> = _isRetrying.asStateFlow()

    init {
        // Reloads on every session change, not once at construction - see
        // TeacherDashboardViewModel's init for why a one-shot load left the
        // previous account's numbers on screen after a different account
        // logged in.
        viewModelScope.launch {
            sessionManager.currentUser.collect { user ->
                if (user == null) _stats.value = null else loadStats(user.schoolId)
            }
        }
    }

    fun refresh() {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        viewModelScope.launch { loadStats(schoolId) }
    }

    /** Resets FAILED rows to PENDING then flushes immediately, so the retry is visible right away rather than waiting for [com.muslimedu.attendance.sync.SyncWorker]'s next periodic run. */
    fun retryFailedSyncs() {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        viewModelScope.launch {
            _isRetrying.value = true
            attendanceDao.resetFailedToPending()
            syncQueueManager.flush()
            loadStats(schoolId)
            _isRetrying.value = false
        }
    }

    private suspend fun loadStats(schoolId: Int) {
        val today = LocalDate.now().toString()
        _stats.value = AdminStats(
            totalStudents = studentDao.countForSchool(schoolId),
            studentsWithFace = faceTemplateDao.countActiveForSchool(schoolId),
            studentsWithRfid = studentDao.countWithRfidForSchool(schoolId),
            syncedToday = attendanceDao.countByStatusOnDate(schoolId, today, AttendanceEntity.SYNC_SYNCED),
            pendingToday = attendanceDao.countByStatusOnDate(schoolId, today, AttendanceEntity.SYNC_PENDING),
            failedToday = attendanceDao.countByStatusOnDate(schoolId, today, AttendanceEntity.SYNC_FAILED),
            failedMessages = attendanceDao.getFailedMessagesOnDate(schoolId, today),
        )
    }
}
