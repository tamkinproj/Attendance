package com.muslimedu.attendance.data.repository

import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.rfid.normalizeRfidUid
import com.muslimedu.attendance.security.AuditLogger
import javax.inject.Inject
import javax.inject.Singleton

sealed class CardAssignResult {
    data class Assigned(val replacedUid: String?) : CardAssignResult()

    /** The student already has [currentUid] - ask before replacing (the old card is deactivated). */
    data class NeedsReplace(val currentUid: String) : CardAssignResult()

    /** The card belongs to another student - it must be deactivated there first. */
    data class OwnedByOther(val owner: StudentEntity) : CardAssignResult()
}

/**
 * Looks up students by RFID UID against the local offline cache, filled by
 * the student download ([StudentDownloadRepository]) or added by hand. There
 * is no sample/demo data: every student and card here is real.
 */
@Singleton
class StudentRepository @Inject constructor(
    private val studentDao: StudentDao,
    private val auditLogger: AuditLogger,
    private val deviceSettings: DeviceSettings,
) {
    /**
     * Scan-time lookup, scoped to this device's school ([DeviceSettings]) -
     * not the logged-in account's, since the gate works with nobody logged
     * in. A device that held another school's cache from before offline
     * mode existed can't resolve that school's cards here.
     */
    suspend fun findByRfid(rfidUid: String): StudentEntity? =
        studentDao.findByRfidInSchool(deviceSettings.schoolId.value, normalizeRfidUid(rfidUid))

    suspend fun findByCode(code: String): StudentEntity? =
        studentDao.findByCodeInSchool(deviceSettings.schoolId.value, code)

    /** Re-queues card registrations the server refused, e.g. after the conflict was fixed on the web. */
    suspend fun retryFailedCards() = studentDao.retryFailedRfid(deviceSettings.schoolId.value)

    /** This device's students - see [findByRfid] for the scoping. */
    suspend fun getAll(): List<StudentEntity> = studentDao.getAllForSchool(deviceSettings.schoolId.value)

    /**
     * Registers [rfidUid] as [student]'s one active card, on this device and
     * (queued, see [com.muslimedu.attendance.sync.RfidCardSyncManager]) in
     * the server's card registry. Never creates a student - the card is
     * attached to the existing record.
     *
     * One card, one student: a card registered to someone else is refused
     * (deactivate it there first). A student who already has a different
     * card needs [replace] = true, which deactivates the old card - the
     * lost/replaced card case.
     */
    suspend fun assignRfidCard(student: StudentEntity, rfidUid: String, replace: Boolean = false): CardAssignResult {
        val uid = normalizeRfidUid(rfidUid)
        val owner = studentDao.findAnyByRfid(uid)
        if (owner != null && owner.id != student.id) return CardAssignResult.OwnedByOther(owner)
        val current = studentDao.findBySchoolAndStudentId(student.schoolId, student.studentId) ?: student
        val oldUid = current.rfidCardNumber?.takeIf { it != uid }
        if (oldUid != null && !replace) return CardAssignResult.NeedsReplace(oldUid)

        studentDao.setRfidCardPending(student.schoolId, student.studentId, uid, System.currentTimeMillis())
        auditLogger.log(
            action = AuditLogger.ACTION_RFID_ASSIGNED,
            entityType = "student",
            entityId = student.studentId,
            details = "rfidUid=$uid" + (oldUid?.let { ", replaced=$it (deactivated)" } ?: ""),
        )
        return CardAssignResult.Assigned(replacedUid = oldUid)
    }

    /** Deactivates [student]'s card here and (queued) on the server. The card can then be registered to someone else. */
    suspend fun deactivateRfidCard(student: StudentEntity) {
        val uid = student.rfidCardNumber ?: return
        studentDao.setRfidCardPending(student.schoolId, student.studentId, null, System.currentTimeMillis())
        auditLogger.log(
            action = AuditLogger.ACTION_RFID_ASSIGNED,
            entityType = "student",
            entityId = student.studentId,
            details = "rfidUid=$uid deactivated",
        )
    }

    suspend fun reload(student: StudentEntity): StudentEntity? =
        studentDao.findBySchoolAndStudentId(student.schoolId, student.studentId)

    /**
     * Adds a student by hand on this device. There's no backend endpoint to
     * create a student, but gate scans sync by `code` alone, so as long as
     * [code] is the student's real school code their gate scans upload fine
     * - a wrong code is rejected by the server at sync time (visible on the
     * Sync screen), never silently. [studentId] is one below the lowest id
     * in the table: real server ids are always positive, so this can't
     * collide with one a later student download brings in (that download
     * matches on code and replaces this id with the real one).
     */
    suspend fun addLocalStudent(name: String, code: String, sectionName: String?): Result<StudentEntity> {
        val schoolId = deviceSettings.schoolId.value
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
}
