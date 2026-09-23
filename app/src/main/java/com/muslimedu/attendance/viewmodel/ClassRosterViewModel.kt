package com.muslimedu.attendance.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.repository.AttendanceRepository
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** [checkInTime] is set only if this student has already been scanned present today. */
data class RosterRow(val student: StudentEntity, val checkInTime: String?)

@HiltViewModel
class ClassRosterViewModel @Inject constructor(
    private val studentDao: StudentDao,
    private val attendanceDao: AttendanceDao,
    private val sessionManager: SessionManager,
    private val photoCache: StudentPhotoCache,
    private val attendanceRepository: AttendanceRepository,
) : ViewModel() {

    private val _rows = MutableStateFlow<List<RosterRow>>(emptyList())
    val rows: StateFlow<List<RosterRow>> = _rows.asStateFlow()

    /** Null when no class is active yet (e.g. "Continue Offline" was chosen before any sync) - the screen shows a message rather than silently listing the whole school. */
    private val _sectionName = MutableStateFlow<String?>(null)
    val sectionName: StateFlow<String?> = _sectionName.asStateFlow()

    init {
        // Reloads on session change - see TeacherDashboardViewModel's init.
        viewModelScope.launch {
            sessionManager.currentUser.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val schoolId = sessionManager.currentUser.value?.schoolId
            val sectionId = sessionManager.activeClass.value?.sectionId
            if (schoolId == null || sectionId == null) {
                _rows.value = emptyList()
                _sectionName.value = null
                return@launch
            }
            val today = LocalDate.now().toString()
            val students = studentDao.findBySchoolAndSection(schoolId, sectionId)
            _rows.value = students.map { student ->
                val existing = attendanceDao.findForStudentOnDate(student.schoolId, student.studentId, today)
                RosterRow(student, checkInTime = existing?.checkInTime)
            }
            _sectionName.value = students.firstOrNull()?.sectionName
        }
    }

    suspend fun loadPhoto(student: StudentEntity): Bitmap? =
        photoCache.loadCachedBitmap(student.schoolId, student.studentId)

    /**
     * Admin-only manual override for a student the reader can't otherwise
     * mark present (a broken/missing card, say) - [AttendanceRepository.recordScan]
     * already supports this via its `manualOverride` flag (sets `overrideBy`
     * and logs [com.muslimedu.attendance.security.AuditLogger.ACTION_MANUAL_OVERRIDE]),
     * there just wasn't a UI trigger for it anywhere before this. Guarded
     * against a double-tap the same way the RFID flow guards a duplicate scan.
     */
    fun markPresentManually(student: StudentEntity) {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val existing = attendanceDao.findForStudentOnDate(student.schoolId, student.studentId, today)
            if (existing == null) {
                attendanceRepository.recordScan(
                    student = student,
                    rfidUid = null,
                    verifiedByRfid = false,
                    manualOverride = true,
                )
            }
            refresh()
        }
    }
}
