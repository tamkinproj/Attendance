package com.muslimedu.attendance.data.repository

import androidx.room.withTransaction
import com.muslimedu.attendance.data.db.AppDatabase
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.local.StudentPhotoCache
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateStudentDto
import com.muslimedu.attendance.data.remote.dto.GateStudentsRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.rfid.normalizeRfidUid
import com.muslimedu.attendance.util.normalizePhMobile
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class StudentDownloadSummary(val added: Int, val updated: Int, val skipped: Int)

/**
 * The school server doesn't have `admin_gate_students` yet. Not an error the
 * admin can fix from the device - screens show it as "not available yet"
 * rather than a failure, and students can still be added by hand.
 */
class StudentDownloadUnavailableException : Exception(
    "Student download isn't set up on the school server yet. Add students by hand in Admin > Students until it is.",
)

/**
 * How a Laravel server answers a route that doesn't exist: 404, or 405 when
 * a GET-only catch-all (`Route::fallback()`, an SPA route) matches the URL -
 * Laravel then reports "POST not supported, supported methods: GET, HEAD"
 * instead of "not found". Both come with a JSON `message`, so the message
 * can't be used to tell them apart from a real error; the status code can.
 */
internal fun isMissingEndpoint(httpCode: Int): Boolean = httpCode == 404 || httpCode == 405 || httpCode == 501

/**
 * Downloads the whole school's student list (`admin_gate_students`) into the
 * local cache so gate scanning works offline - including each student's
 * registered RFID card when the server keeps the card registry, and the
 * parent number the gate texts go to.
 *
 * Merged by `code` - the one identifier the backend and this device share.
 * A student already on the device (downloaded before, or added by hand)
 * keeps their local row, RFID card and face template; only the server-owned
 * fields are refreshed, and a hand-added student's temporary negative id is
 * replaced by the real one (their face template follows). A merge, not a
 * reconciliation: a student missing from the download is left in place.
 */
@Singleton
class StudentDownloadRepository @Inject constructor(
    private val apiService: ApiService,
    private val database: AppDatabase,
    private val deviceSettings: DeviceSettings,
    private val photoCache: StudentPhotoCache,
) {
    suspend fun download(): Result<StudentDownloadSummary> {
        if (!deviceSettings.isBound) return Result.failure(Exception("Sign in as a school admin first"))
        val data = try {
            val response = apiService.adminGateStudents(GateStudentsRequest())
            if (!response.success) return Result.failure(Exception(response.message ?: "Download failed"))
            response.data ?: return Result.failure(Exception("The server did not return a student list"))
        } catch (e: HttpException) {
            if (isMissingEndpoint(e.code())) return Result.failure(StudentDownloadUnavailableException())
            return Result.failure(Exception(e.extractApiErrorMessage() ?: "Download failed (${e.code()})"))
        } catch (e: IOException) {
            return Result.failure(Exception("Network error - check your connection"))
        }

        val students = data.students ?: return Result.failure(Exception("The server did not return a student list"))
        val schoolId = deviceSettings.schoolId.value
        val summary = merge(schoolId, students, rfidManaged = data.rfidManaged == true, phonesManaged = data.parentPhoneManaged == true)
        deviceSettings.lastStudentDownloadAt = System.currentTimeMillis()
        students.forEach { photoCache.prefetch(schoolId, it.studentId, it.photo) }
        return Result.success(summary)
    }

    private suspend fun merge(
        schoolId: Int,
        students: List<GateStudentDto>,
        rfidManaged: Boolean,
        phonesManaged: Boolean,
    ): StudentDownloadSummary {
        var added = 0
        var updated = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val studentDao = database.studentDao()
        val faceTemplateDao = database.faceTemplateDao()

        database.withTransaction {
            for (dto in students) {
                val code = dto.code?.trim()
                if (code.isNullOrEmpty()) {
                    skipped++
                    continue
                }
                val existing = studentDao.findByCode(code)
                if (existing != null && existing.schoolId != schoolId) {
                    // Code is taken by another school's leftover cache row
                    // (unique index is table-wide) - don't overwrite it.
                    skipped++
                    continue
                }
                if (existing != null && existing.studentId != dto.studentId) {
                    faceTemplateDao.reassignStudent(schoolId, existing.studentId, dto.studentId)
                }
                val card = if (rfidManaged) cardFromServer(existing, code, dto.rfidUid, schoolId, now) else null
                val phone = if (phonesManaged) phoneFromServer(existing, dto) else PhoneState.keep(existing)
                studentDao.insert(
                    StudentEntity(
                        id = existing?.id ?: 0,
                        schoolId = schoolId,
                        studentId = dto.studentId,
                        name = dto.name ?: existing?.name ?: code,
                        code = code,
                        photoUrl = dto.photo ?: existing?.photoUrl,
                        gender = dto.gender ?: existing?.gender,
                        sectionId = dto.sectionId ?: existing?.sectionId,
                        sectionName = dto.sectionName ?: existing?.sectionName,
                        rfidCardNumber = if (card != null) card.uid else existing?.rfidCardNumber,
                        isLocalOnly = false,
                        lastSyncedAt = now,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                        rfidSyncStatus = if (card != null) StudentEntity.RFID_SYNCED else existing?.rfidSyncStatus ?: StudentEntity.RFID_SYNCED,
                        rfidSyncError = if (card != null) null else existing?.rfidSyncError,
                        parentPhone = phone.phone,
                        hasParentAccount = if (phonesManaged) dto.hasParentAccount else existing?.hasParentAccount,
                        phoneSyncStatus = phone.status,
                        phoneSyncError = phone.error,
                    ),
                )
                if (existing == null) added++ else updated++
            }
        }
        return StudentDownloadSummary(added, updated, skipped)
    }

    private class PhoneState(val phone: String?, val status: String, val error: String?) {
        companion object {
            fun keep(existing: StudentEntity?) = PhoneState(
                existing?.parentPhone,
                existing?.phoneSyncStatus ?: StudentEntity.RFID_SYNCED,
                existing?.phoneSyncError,
            )
        }
    }

    /**
     * The parent number per the server, unless the admin changed it on this
     * device and that hasn't been accepted yet - the device's number wins
     * then, same rule as cards. One refused earlier goes back in the upload
     * queue once the server reports a parent account (it was most likely
     * refused for having none).
     */
    private fun phoneFromServer(existing: StudentEntity?, dto: GateStudentDto): PhoneState = when {
        existing?.phoneSyncStatus == StudentEntity.RFID_PENDING -> PhoneState.keep(existing)
        existing?.phoneSyncStatus == StudentEntity.RFID_FAILED ->
            if (dto.hasParentAccount == true) PhoneState(existing?.parentPhone, StudentEntity.RFID_PENDING, null) else PhoneState.keep(existing)
        else -> PhoneState(dto.parentPhone?.let { normalizePhMobile(it) ?: it.trim().ifEmpty { null } }, StudentEntity.RFID_SYNCED, null)
    }

    /** A card decision from the server's registry; [uid] null means "no card". */
    private class ServerCard(val uid: String?)

    /**
     * The card this student should have per the server, or null to keep
     * what's on the device. The device's card wins while the admin's own
     * change there hasn't been sent (pending) or was refused (failed) - the
     * server doesn't know about it yet. A card the server has moved to this
     * student is taken off whoever held it here, as long as that holder has
     * no unsent change of their own; otherwise both are left as they are
     * and the next upload sorts it out.
     */
    private suspend fun cardFromServer(existing: StudentEntity?, code: String, serverUid: String?, schoolId: Int, now: Long): ServerCard? {
        if (existing != null && existing.rfidSyncStatus != StudentEntity.RFID_SYNCED) return null
        val uid = serverUid?.let(::normalizeRfidUid)?.takeIf { it.isNotEmpty() }
        if (uid == null || uid == existing?.rfidCardNumber) return ServerCard(uid)
        val holder = database.studentDao().findAnyByRfid(uid)
        if (holder != null && holder.code != code) {
            if (holder.schoolId != schoolId || holder.rfidSyncStatus != StudentEntity.RFID_SYNCED) return null
            database.studentDao().clearRfid(holder.id, now)
        }
        return ServerCard(uid)
    }
}
