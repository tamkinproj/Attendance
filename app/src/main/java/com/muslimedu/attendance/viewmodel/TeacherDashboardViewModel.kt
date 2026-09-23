package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.rfid.ReaderStatus
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * Present is the only real attendance-status count this app tracks today -
 * `AttendanceEntity.status` is always forced to "present" by the scan flow
 * (see CLAUDE.md's "Confirmed against the real backend" notes; there is no
 * absent/late marking anywhere yet). Rather than fabricate Absent/Late
 * numbers with no backing data, [remainingInRoster] reports the honest
 * complement: how many of the currently-cached roster's students haven't
 * been scanned yet today.
 */
data class TeacherDashboardStats(
    val teacherName: String,
    val today: String,
    val presentToday: Int,
    val rosterSize: Int,
) {
    val remainingInRoster: Int get() = (rosterSize - presentToday).coerceAtLeast(0)
}

@HiltViewModel
class TeacherDashboardViewModel @Inject constructor(
    private val studentDao: StudentDao,
    private val attendanceDao: AttendanceDao,
    private val sessionManager: SessionManager,
    private val rfidManager: RfidManager,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _stats = MutableStateFlow<TeacherDashboardStats?>(null)
    val stats: StateFlow<TeacherDashboardStats?> = _stats.asStateFlow()

    val readerStatus: StateFlow<ReaderStatus> = rfidManager.status
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    init {
        // Idempotent (see RfidManager.register's own doc comment) - safe to
        // call again even though RfidViewModel's init already does this too.
        rfidManager.register()

        // Reload on every session change rather than once at construction.
        // This ViewModel is Activity-scoped and logging out doesn't kill the
        // process, so a one-shot `init { refresh() }` left the *previous*
        // account's name and counts on screen after someone else logged in -
        // the top app bar reads the live session and updated, the dashboard
        // body didn't. currentUser is a StateFlow, so the first emission here
        // does the initial load that init used to.
        viewModelScope.launch {
            sessionManager.currentUser.collect { user ->
                if (user == null) _stats.value = null else loadStats(user.name, user.schoolId)
            }
        }
    }

    fun refresh() {
        val user = sessionManager.currentUser.value ?: return
        viewModelScope.launch { loadStats(user.name, user.schoolId) }
    }

    private suspend fun loadStats(teacherName: String, schoolId: Int) {
        val today = LocalDate.now()
        val todayString = today.toString()
        val activeSectionId = sessionManager.activeClass.value?.sectionId

        val rosterSize = if (activeSectionId != null) {
            studentDao.findBySchoolAndSection(schoolId, activeSectionId).size
        } else {
            studentDao.countForSchool(schoolId)
        }

        _stats.value = TeacherDashboardStats(
            teacherName = teacherName,
            today = today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            presentToday = attendanceDao.countRecordedOnDate(schoolId, todayString),
            rosterSize = rosterSize,
        )
    }
}
