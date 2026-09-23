package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.entities.AttendanceEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.sync.IdempotencyHandler
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttendanceRepository @Inject constructor(
    private val attendanceDao: AttendanceDao,
    private val sessionManager: SessionManager,
    private val auditLogger: AuditLogger,
) {
    /** Existing record for this student today, if any - used for duplicate-scan detection. */
    suspend fun findExistingToday(schoolId: Int, studentId: Int): AttendanceEntity? =
        attendanceDao.findForStudentOnDate(schoolId, studentId, todayString())

    /**
     * Records a scan as a pending local row. [SyncQueueManager] picks it up
     * from there - this does not itself call the network.
     *
     * Falls back to the matched student's own cached section (and subject_id
     * 0, since there's no real subject without a live class selection) when
     * no class was synced this session, e.g. after "Continue Offline". A
     * scan recorded that way still saves locally and is still synced once a
     * real class is selected, just without a real subject_id until then.
     */
    suspend fun recordScan(
        student: StudentEntity,
        rfidUid: String?,
        verifiedByRfid: Boolean,
        verifiedByFace: Boolean = false,
        faceMatchScore: Float? = null,
        manualOverride: Boolean = false,
    ): AttendanceEntity {
        // Always the matched student's OWN school_id, never the current
        // session's - the two agree for a real roster-synced student (whose
        // school_id was set from the session at sync time anyway), but not
        // for the sample/demo seed data, which is hardcoded to school_id = 1
        // regardless of which real account is logged in. Preferring the
        // session's school_id here used to file a demo student's attendance
        // under the real admin's school_id, which SyncQueueManager's later
        // findBySchoolAndStudentId(record.schoolId, record.studentId) lookup
        // could never match against the demo row (still at school_id = 1) -
        // permanently failing with "Student no longer in local cache" even
        // though the row was right there. This field must always match
        // whatever school_id the looked-up student row itself carries, since
        // that's the only thing this ever gets looked up against again.
        val schoolId = student.schoolId
        val active = sessionManager.activeClass.value
        val now = System.currentTimeMillis()

        val entity = AttendanceEntity(
            schoolId = schoolId,
            sectionId = active?.sectionId ?: student.sectionId ?: 0,
            subjectId = active?.subjectId ?: 0,
            studentId = student.studentId,
            scanDate = todayString(),
            checkInTime = LocalTime.now().format(TIME_FORMATTER),
            rfidUid = rfidUid,
            verifiedByRfid = verifiedByRfid,
            verifiedByFace = verifiedByFace,
            faceMatchScore = faceMatchScore,
            manualOverride = manualOverride,
            overrideBy = if (manualOverride) sessionManager.currentUser.value?.email else null,
            idempotencyKey = IdempotencyHandler.newKey(),
            createdAt = now,
            updatedAt = now,
        )
        val id = attendanceDao.insert(entity)

        auditLogger.log(
            action = if (manualOverride) AuditLogger.ACTION_MANUAL_OVERRIDE else AuditLogger.ACTION_ATTENDANCE_RECORDED,
            entityType = "student",
            entityId = student.studentId,
            details = "status=${entity.status} verifiedByRfid=$verifiedByRfid verifiedByFace=$verifiedByFace",
        )

        return entity.copy(id = id)
    }

    private fun todayString(): String = LocalDate.now().toString()

    companion object {
        // The backend validates check_in_time as 'date_format:H:i' (confirmed
        // from teacher_attendance_submit's validation rules) - no seconds.
        // Sending "HH:mm:ss" here meant every single submit failed Laravel's
        // format check and came back as a 422, which SyncQueueManager then
        // marked permanently failed: attendance was never actually reaching
        // the real backend at all, regardless of how correct everything else
        // in the sync pipeline was.
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    }
}
