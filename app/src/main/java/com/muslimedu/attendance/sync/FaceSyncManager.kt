package com.muslimedu.attendance.sync

import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.local.SettingsRepository
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.StudentFaceAngleDto
import com.muslimedu.attendance.data.remote.dto.StudentFaceDto
import com.muslimedu.attendance.data.remote.dto.StudentFaceSetRequest
import com.muslimedu.attendance.data.remote.dto.StudentFacesRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.data.repository.FaceTemplateRepository
import com.muslimedu.attendance.face.FaceCodec
import com.muslimedu.attendance.face.PortableFace
import com.muslimedu.attendance.face.PortableFaceAngle
import com.muslimedu.attendance.security.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class FaceSyncOutcome(
    val uploaded: Int = 0,
    val failed: Int = 0,
    val downloaded: Int = 0,
    /** Why it stopped early (offline, server not updated...); null = all done. */
    val stoppedReason: String? = null,
)

/**
 * Shares registered faces between the school's gate phones through the
 * school server, when Face Settings allows it ([SettingsRepository.shareFaces]):
 *
 * - **Upload**: each registration made on this phone (pending) goes to
 *   `/admin_student_face_set` - every angle, with its version. A refusal
 *   the admin must fix (student not on the server) is marked failed.
 * - **Download**: `/admin_student_faces` since the last download; each face
 *   replaces this phone's copy unless it's the same version, or this phone
 *   has its own registration for that student that the server doesn't have
 *   yet (pending or failed - the phone's wins, same rule as cards).
 *
 * Runs with every gate sync, after attendance so it never delays a check-in;
 * downloads at most every [DOWNLOAD_EVERY_MILLIS] unless forced (after the
 * student list is downloaded).
 */
@Singleton
class FaceSyncManager @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val deviceSettings: DeviceSettings,
    private val settingsRepository: SettingsRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val faceTemplateDao: FaceTemplateDao,
    private val studentDao: StudentDao,
) {
    private val mutex = Mutex()
    private var lastDownloadAt = 0L

    private val ready: Boolean
        get() = settingsRepository.shareFaces.value && tokenManager.getToken() != null && deviceSettings.isBound

    suspend fun sync(forceDownload: Boolean = false): FaceSyncOutcome {
        if (!ready) return FaceSyncOutcome()
        return mutex.withLock {
            val up = uploadLocked()
            if (up.stoppedReason != null) return@withLock up
            val down = downloadLocked(forceDownload)
            up.copy(downloaded = down.downloaded, stoppedReason = down.stoppedReason)
        }
    }

    /** Students with a face this phone hasn't shared yet (waiting or refused). */
    suspend fun unsentCount(): Int = faceTemplateDao.unsentCount(deviceSettings.schoolId.value)

    private sealed class Step {
        data object Done : Step()
        data object Failed : Step()
        data class Stop(val reason: String) : Step()
    }

    private suspend fun uploadLocked(): FaceSyncOutcome {
        val schoolId = deviceSettings.schoolId.value
        var uploaded = 0
        var failed = 0
        for (studentId in faceTemplateDao.pendingStudentIds(schoolId)) {
            val face = faceTemplateRepository.exportFace(schoolId, studentId) ?: continue
            val student = studentDao.findBySchoolAndStudentId(schoolId, studentId)
            if (student == null) {
                faceTemplateDao.updateSyncState(schoolId, studentId, face.version, FaceTemplateEntity.SYNC_FAILED, "Student isn't on this phone any more")
                failed++
                continue
            }
            val request = StudentFaceSetRequest(
                code = student.code,
                model = face.model,
                version = face.version,
                deviceUid = deviceSettings.deviceUid,
                templates = face.angles.map { StudentFaceAngleDto(it.pose, FaceCodec.toBase64(it.embedding), it.livenessScore) },
            )
            when (val step = call(face.version, schoolId, studentId) { apiService.adminStudentFaceSet(request).success }) {
                Step.Done -> uploaded++
                Step.Failed -> failed++
                is Step.Stop -> return FaceSyncOutcome(uploaded, failed, 0, step.reason)
            }
        }
        return FaceSyncOutcome(uploaded, failed)
    }

    private suspend fun call(version: String, schoolId: Int, studentId: Int, block: suspend () -> Boolean): Step = try {
        if (block()) {
            faceTemplateDao.updateSyncState(schoolId, studentId, version, FaceTemplateEntity.SYNC_SYNCED, null)
            Step.Done
        } else {
            faceTemplateDao.updateSyncState(schoolId, studentId, version, FaceTemplateEntity.SYNC_FAILED, "Refused by the server")
            Step.Failed
        }
    } catch (e: HttpException) {
        val message = e.extractApiErrorMessage()
        when (e.code()) {
            404, 422 -> {
                faceTemplateDao.updateSyncState(schoolId, studentId, version, FaceTemplateEntity.SYNC_FAILED, message ?: "Refused by the server")
                Step.Failed
            }
            401 -> Step.Stop("Session expired - sign out and sign in again")
            405, 501 -> Step.Stop(NOT_ON_SERVER)
            else -> Step.Stop(message ?: "Server error (${e.code()})")
        }
    } catch (e: IOException) {
        Step.Stop("No connection")
    }

    private suspend fun downloadLocked(force: Boolean): FaceSyncOutcome {
        val now = System.currentTimeMillis()
        if (!force && now - lastDownloadAt < DOWNLOAD_EVERY_MILLIS) return FaceSyncOutcome()
        lastDownloadAt = now
        val data = try {
            val response = apiService.adminStudentFaces(StudentFacesRequest(deviceSettings.faceDownloadSince))
            response.data?.takeIf { response.success } ?: return FaceSyncOutcome(stoppedReason = response.message ?: "Refused by the server")
        } catch (e: HttpException) {
            return FaceSyncOutcome(
                stoppedReason = when (e.code()) {
                    404, 405, 501 -> NOT_ON_SERVER
                    401 -> "Session expired - sign out and sign in again"
                    else -> e.extractApiErrorMessage() ?: "Server error (${e.code()})"
                },
            )
        } catch (e: IOException) {
            return FaceSyncOutcome(stoppedReason = "No connection")
        }

        val schoolId = deviceSettings.schoolId.value
        var downloaded = 0
        var studentMissing = false
        for (dto in data.faces.orEmpty()) {
            when (applyDownloaded(schoolId, dto)) {
                Applied.Replaced -> downloaded++
                Applied.StudentNotHere -> studentMissing = true
                Applied.Kept -> Unit
            }
        }
        // A face for a student this phone doesn't have yet (student list not
        // downloaded since) must come again: only move on when none was missed.
        if (!studentMissing) data.serverTime?.let { deviceSettings.faceDownloadSince = it }
        return FaceSyncOutcome(downloaded = downloaded)
    }

    private enum class Applied { Replaced, Kept, StudentNotHere }

    private suspend fun applyDownloaded(schoolId: Int, dto: StudentFaceDto): Applied {
        val face = toPortable(dto) ?: return Applied.Kept
        val student = dto.code?.let { studentDao.findByCodeInSchool(schoolId, it) } ?: return Applied.StudentNotHere
        val local = faceTemplateRepository.syncState(schoolId, student.studentId)
        if (local != null) {
            val (version, status) = local
            if (version == face.version) return Applied.Kept
            // This phone's own registration the server doesn't have yet wins.
            if (status != FaceTemplateEntity.SYNC_SYNCED) return Applied.Kept
        }
        faceTemplateRepository.replaceFace(schoolId, student.studentId, face, FaceTemplateEntity.SYNC_SYNCED, source = "shared")
        return Applied.Replaced
    }

    companion object {
        const val DOWNLOAD_EVERY_MILLIS = 5 * 60_000L
        const val NOT_ON_SERVER = "Face sharing isn't set up on the school server yet - faces stay on this phone"

        /** A downloaded face this phone can use, or null (another model, damaged numbers, no angles). */
        fun toPortable(dto: StudentFaceDto): PortableFace? {
            if (dto.model != FaceTemplateEntity.MODEL_MOBILEFACENET) return null
            val version = dto.version?.takeIf { it.isNotBlank() } ?: return null
            val angles = dto.templates.orEmpty().map { angle ->
                val embedding = FaceCodec.mobileFaceNetFromBase64(angle.embedding) ?: return null
                PortableFaceAngle(angle.pose, embedding, angle.livenessScore ?: 0f)
            }
            if (angles.isEmpty()) return null
            return PortableFace(FaceTemplateEntity.MODEL_MOBILEFACENET, version, angles)
        }
    }
}
