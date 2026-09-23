package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.session.SessionManager
import com.muslimedu.attendance.security.AuditLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up students by RFID UID against the local offline cache. Real roster
 * data comes from [com.muslimedu.attendance.data.repository.RosterRepository]
 * syncing `/teacher_attendance_roster`; [seedSampleDataIfEmpty] only fills the
 * cache with a few test students when that hasn't run yet (e.g. no backend
 * access, or the user picked "Continue Offline" before ever syncing).
 */
@Singleton
class StudentRepository @Inject constructor(
    private val studentDao: StudentDao,
    private val attendanceDao: AttendanceDao,
    private val auditLogger: AuditLogger,
    private val sessionManager: SessionManager,
) {
    /**
     * Scan-time lookup, scoped to the logged-in account's school. The local
     * cache outlives a logout, so a device that's had two accounts on it
     * holds both schools' students - without this scope, scanning a card
     * cached under the *previous* account would resolve to that other
     * school's student and file attendance nobody can sync.
     *
     * Returns null when nothing is logged in: there's no school to scan
     * against, and no screen that scans is reachable logged out anyway.
     */
    suspend fun findByRfid(rfidUid: String): StudentEntity? {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return null
        return studentDao.findByRfidInSchool(schoolId, rfidUid)
    }

    /** The logged-in account's own roster - see [findByRfid] for why this is school-scoped. */
    suspend fun getAll(): List<StudentEntity> {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return emptyList()
        return studentDao.getAllForSchool(schoolId)
    }

    /**
     * Assigns [rfidUid] to [student]. Fails without writing anything if that
     * UID is already assigned to a *different* student - re-assigning the
     * same student to the same card they already have is a no-op success.
     */
    suspend fun assignRfidCard(student: StudentEntity, rfidUid: String): Result<Unit> {
        val existingOwner = studentDao.findByRfid(rfidUid)
        if (existingOwner != null && existingOwner.studentId != student.studentId) {
            return Result.failure(Exception("This card is already assigned to ${existingOwner.name}"))
        }
        studentDao.updateRfidCardNumber(student.schoolId, student.studentId, rfidUid, System.currentTimeMillis())
        auditLogger.log(
            action = AuditLogger.ACTION_RFID_ASSIGNED,
            entityType = "student",
            entityId = student.studentId,
            details = "rfidUid=$rfidUid",
        )
        return Result.success(Unit)
    }

    /**
     * Adds a student that exists only on this device: there is no backend
     * endpoint to create a student (only login/roster/attendance exist per
     * the spec), so this can't be synced. [studentId] is assigned as one
     * below the lowest id currently in the table, which - since real synced
     * ids come from the server and are always positive - stays clear of any
     * id a future roster sync could introduce.
     */
    suspend fun addLocalStudent(name: String, code: String, sectionName: String?): Result<StudentEntity> {
        val schoolId = sessionManager.currentUser.value?.schoolId
            ?: return Result.failure(Exception("Not logged in"))
        if (studentDao.findByCode(code) != null) {
            return Result.failure(Exception("A student with code \"$code\" already exists"))
        }

        val nextStudentId = ((studentDao.minStudentId() ?: 0).coerceAtMost(0)) - 1
        val now = System.currentTimeMillis()
        val student = StudentEntity(
            schoolId = schoolId,
            studentId = nextStudentId,
            name = name,
            code = code,
            sectionName = sectionName,
            rfidCardNumber = null,
            isLocalOnly = true,
            createdAt = now,
            updatedAt = now,
        )
        studentDao.insert(student)
        auditLogger.log(
            action = AuditLogger.ACTION_STUDENT_ADDED,
            entityType = "student",
            entityId = nextStudentId,
            details = "name=$name, code=$code",
        )
        return Result.success(student)
    }

    suspend fun seedSampleDataIfEmpty() {
        // Must run unconditionally, even when seeding itself is skipped
        // below - an install where these rows were already seeded before
        // isLocalOnly existed on them needs this to ever get corrected;
        // seeding only ever happens once (see the early return right after).
        studentDao.markLocalOnlyByCode(SAMPLE_STUDENT_CODES)
        // Same reasoning: repairs any attendance row already stuck permanently
        // failed from the school_id mismatch bug fixed in
        // AttendanceRepository.recordScan() - see AttendanceDao.repairDemoAttendanceSchoolId()'s
        // doc comment for the full story.
        attendanceDao.repairDemoAttendanceSchoolId()

        if (studentDao.count() > 0) return
        val now = System.currentTimeMillis()
        studentDao.insertAll(
            listOf(
                // isLocalOnly = true on all three: this is demo data with no
                // corresponding row on the real backend at all (student_id
                // 1/2/3 and code "STU00N" are fabricated, not synced from any
                // real roster). Without this flag, SyncQueueManager had no
                // way to tell these apart from a real synced student, so it
                // dutifully tried to sync their attendance to the real
                // server on every scan - and since no backend student is
                // ever enrolled under a demo code like "STU001", every one
                // of those calls failed, permanently, every time. That was
                // the actual cause behind a persistent "Failed: 1" (or more)
                // on the Admin Dashboard's sync status for anyone testing
                // against this seed data before a real roster sync.
                StudentEntity(
                    schoolId = 1, studentId = 1, name = "Mohammed Ahmed", code = "STU001",
                    sectionId = 5, sectionName = "Grade 5A", rfidCardNumber = "04:1A:2B:3C",
                    isLocalOnly = true, createdAt = now, updatedAt = now,
                ),
                StudentEntity(
                    schoolId = 1, studentId = 2, name = "Fatima Al-Zahra", code = "STU002",
                    sectionId = 5, sectionName = "Grade 5A", rfidCardNumber = "04:5D:6E:7F",
                    isLocalOnly = true, createdAt = now, updatedAt = now,
                ),
                StudentEntity(
                    schoolId = 1, studentId = 3, name = "Yusuf Ibrahim", code = "STU003",
                    sectionId = 5, sectionName = "Grade 5A", rfidCardNumber = "09:AA:BB:CC",
                    isLocalOnly = true, createdAt = now, updatedAt = now,
                ),
            ),
        )
    }

    companion object {
        private val SAMPLE_STUDENT_CODES = listOf("STU001", "STU002", "STU003")
    }
}
