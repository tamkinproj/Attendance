package com.muslimedu.attendance.sync

import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.AttendanceEntity
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.AttendanceScanRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flushes pending [AttendanceEntity] rows to `/teacher_attendance_scan`, one
 * request per row. Called both opportunistically right after a scan
 * (best-effort, from [com.muslimedu.attendance.data.repository.AttendanceRepository]'s
 * caller) and periodically by [SyncWorker] as the reliability backstop.
 *
 * Deliberately **not** `/teacher_attendance_submit`, even though that
 * endpoint also accepts a `records` array and looks like the obvious fit for
 * "send this scan" - it locks the whole (section, subject, date) roster on
 * its first successful call, so every scan after the first student in a
 * class period would get rejected. See [com.muslimedu.attendance.data.remote.dto.AttendanceSubmitRequest]'s
 * and [com.muslimedu.attendance.data.remote.dto.AttendanceScanRequest]'s doc
 * comments for the full story - this was the reason attendance appeared to
 * record fine on-device ("Present - recorded") but never showed up on the
 * web dashboard past the first student of each period.
 */
@Singleton
class SyncQueueManager @Inject constructor(
    private val apiService: ApiService,
    private val attendanceDao: AttendanceDao,
    private val studentDao: StudentDao,
) {
    // Guards against the opportunistic post-scan flush and SyncWorker's
    // periodic flush racing each other over the same rows.
    private val mutex = Mutex()

    suspend fun flush() = mutex.withLock {
        val now = System.currentTimeMillis()
        attendanceDao.getDueForSync(now).forEach { record -> syncOne(record, now) }
    }

    private suspend fun syncOne(record: AttendanceEntity, now: Long) {
        val student = studentDao.findBySchoolAndStudentId(record.schoolId, record.studentId)
        if (student == null) {
            // Shouldn't happen - the student this attendance row references
            // was in the local cache at scan time - but without a code to
            // send, there is nothing to sync.
            markFailedPermanently(record, now, "Student no longer in local cache")
            return
        }

        // Covers both a student added via the admin Student List's "Add
        // Student" dialog (negative student_id, isLocalOnly = true - see
        // StudentRepository.addLocalStudent) AND the built-in sample/demo
        // roster (StudentRepository.seedSampleDataIfEmpty, also isLocalOnly)
        // used before a real roster has ever been synced. Neither has a
        // matching row on the real backend - checking isLocalOnly directly,
        // rather than inferring it from the sign of student_id, is what
        // actually catches the demo data: its student_id is a small positive
        // int (1, 2, 3), so a sign check alone let it through to the real
        // server, where it 404s every single time (no student anywhere is
        // enrolled under a demo code like "STU001") - the exact "Failed: 1"
        // this always produced. Mark synced immediately instead of letting
        // it hit the server or sit retrying forever.
        if (student.isLocalOnly) {
            attendanceDao.updateSyncResult(
                id = record.id,
                syncStatus = AttendanceEntity.SYNC_SYNCED,
                syncAttempts = record.syncAttempts,
                nextRetryAt = null,
                lastSyncAt = now,
                serverAttendanceId = null,
                errorMessage = "Local-only student - not sent to server",
            )
            return
        }

        try {
            val request = AttendanceScanRequest(
                sectionId = record.sectionId,
                subjectId = record.subjectId,
                date = record.scanDate,
                code = student.code,
            )
            val response = apiService.scanAttendance(request)
            if (response.success) {
                attendanceDao.updateSyncResult(
                    id = record.id,
                    syncStatus = AttendanceEntity.SYNC_SYNCED,
                    syncAttempts = record.syncAttempts,
                    nextRetryAt = null,
                    lastSyncAt = now,
                    serverAttendanceId = response.data?.student?.attendanceId,
                    errorMessage = null,
                )
            } else {
                markFailedPermanently(record, now, response.message ?: "Rejected by server")
            }
        } catch (e: HttpException) {
            when (e.code()) {
                // "You are not assigned to take attendance for this class" -
                // confirmed from assertTeacherAssignedToSection(). Won't
                // succeed on retry: this record's section_id/subject_id are
                // 0/stale, almost always because no roster was ever synced
                // (AttendanceRepository.recordScan falls back to the
                // student's own cached section and subject 0 when
                // SessionManager.activeClass is null) - tell the admin what
                // to actually do about it instead of retrying uselessly.
                403 -> markFailedPermanently(
                    record, now,
                    e.extractApiErrorMessage() ?: "Not assigned to this class - use \"Sync Roster from Server\" to set an active class",
                )
                // "No enrolled student in this class matches that code" -
                // confirmed from teacher_attendance_scan(). Won't succeed on
                // retry: the roster's cached code is stale or the student
                // isn't enrolled in this section/subject on the server.
                404 -> markFailedPermanently(record, now, e.extractApiErrorMessage() ?: "Student not found on server")
                // A bad status value, or a status the school requires a
                // remark for, throws ValidationException server-side -> 422.
                422 -> markFailedPermanently(record, now, e.extractApiErrorMessage() ?: "Validation failed")
                else -> scheduleRetry(record, now, e.extractApiErrorMessage() ?: "Server error (${e.code()})")
            }
        } catch (e: IOException) {
            scheduleRetry(record, now, "Network error")
        }
    }

    private suspend fun scheduleRetry(record: AttendanceEntity, now: Long, message: String) {
        val attempts = record.syncAttempts + 1
        if (attempts >= RetryStrategy.MAX_ATTEMPTS) {
            markFailedPermanently(record, now, "$message (gave up after $attempts attempts)")
            return
        }
        attendanceDao.updateSyncResult(
            id = record.id,
            syncStatus = AttendanceEntity.SYNC_PENDING,
            syncAttempts = attempts,
            nextRetryAt = RetryStrategy.nextRetryAt(record.syncAttempts, now),
            lastSyncAt = now,
            serverAttendanceId = null,
            errorMessage = message,
        )
    }

    private suspend fun markFailedPermanently(record: AttendanceEntity, now: Long, message: String) {
        attendanceDao.updateSyncResult(
            id = record.id,
            syncStatus = AttendanceEntity.SYNC_FAILED,
            syncAttempts = record.syncAttempts + 1,
            nextRetryAt = null,
            lastSyncAt = now,
            serverAttendanceId = null,
            errorMessage = message,
        )
    }
}
