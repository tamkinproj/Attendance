package com.muslimedu.attendance.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.face.FaceCodec
import com.muslimedu.attendance.face.PortableFace
import com.muslimedu.attendance.face.PortableFaceAngle
import com.muslimedu.attendance.rfid.normalizeRfidUid
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.util.BackupFace
import com.muslimedu.attendance.util.BackupFaceAngle
import com.muslimedu.attendance.util.BackupStudent
import com.muslimedu.attendance.util.GateBackup
import com.muslimedu.attendance.util.GateBackupCodec
import com.muslimedu.attendance.util.normalizePhMobile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BackupExport(val uri: Uri, val fileName: String, val students: Int, val cards: Int, val faces: Int, val phones: Int)

data class BackupRestore(
    val cards: Int,
    val faces: Int,
    val phones: Int,
    /** In the backup but not on this phone - download the student list first. */
    val notOnThisPhone: Int,
    /** Card already belongs to another student here - left as it is. */
    val cardConflicts: Int,
)

/**
 * Admin > Sync & Account > Backup: every student's card, parent number and
 * registered face in one password-encrypted file ([GateBackupCodec]) the
 * admin keeps somewhere safe (Drive, a computer), and restoring it on a
 * phone of the same school - a replacement for a lost or broken one, or a
 * second gate. Works without the school server.
 *
 * Restoring only fills in what the backup has; students must already be on
 * the phone (download the student list first). Everything restored is
 * queued for upload like a change made here: cards to the card registry,
 * numbers to the parent accounts, faces to the other gate phones.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val studentDao: StudentDao,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val deviceSettings: DeviceSettings,
    private val auditLogger: AuditLogger,
) {
    suspend fun export(password: CharArray): BackupExport = withContext(Dispatchers.IO) {
        check(deviceSettings.isBound) { "Sign in as a school admin first" }
        val schoolId = deviceSettings.schoolId.value
        val faces = faceTemplateRepository.exportAll(schoolId)
        val students = studentDao.getAllForSchool(schoolId).mapNotNull { s ->
            val face = faces[s.studentId]
            if (s.rfidCardNumber == null && s.parentPhone == null && face == null) return@mapNotNull null
            BackupStudent(
                code = s.code,
                studentId = s.studentId,
                name = s.name,
                rfidUid = s.rfidCardNumber,
                parentPhone = s.parentPhone,
                face = face?.let { f ->
                    BackupFace(f.model, f.version, f.angles.map { BackupFaceAngle(it.pose, FaceCodec.toBase64(it.embedding), it.livenessScore) })
                },
            )
        }
        val bytes = GateBackupCodec.encode(GateBackup(schoolId = schoolId, createdAt = System.currentTimeMillis(), students = students), password)
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val name = "gate-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + "." + GateBackupCodec.FILE_EXTENSION
        val file = File(dir, name).apply { writeBytes(bytes) }
        val result = BackupExport(
            uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
            fileName = name,
            students = students.size,
            cards = students.count { it.rfidUid != null },
            faces = students.count { it.face != null },
            phones = students.count { it.parentPhone != null },
        )
        auditLogger.log(action = AuditLogger.ACTION_BACKUP_EXPORTED, details = "${result.students} students, ${result.cards} cards, ${result.faces} faces, ${result.phones} numbers")
        result
    }

    /** Restores [uri]; throws [GateBackupCodec.BackupException] (or IllegalStateException) with a readable reason. */
    suspend fun restore(uri: Uri, password: CharArray): BackupRestore = withContext(Dispatchers.IO) {
        check(deviceSettings.isBound) { "Sign in as a school admin first" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw GateBackupCodec.BackupException("Couldn't open that file.")
        val backup = GateBackupCodec.decode(bytes, password)
        val schoolId = deviceSettings.schoolId.value
        if (backup.schoolId != schoolId) {
            throw GateBackupCodec.BackupException("This backup is from another school (school #${backup.schoolId}) - it can't be restored here.")
        }

        val now = System.currentTimeMillis()
        var cards = 0
        var faces = 0
        var phones = 0
        var missing = 0
        var conflicts = 0
        for (entry in backup.students) {
            val student = studentDao.findByCodeInSchool(schoolId, entry.code)
            if (student == null) {
                missing++
                continue
            }
            entry.rfidUid?.let(::normalizeRfidUid)?.takeIf { it.isNotEmpty() && it != student.rfidCardNumber }?.let { uid ->
                val holder = studentDao.findAnyByRfid(uid)
                if (holder != null && holder.id != student.id) {
                    conflicts++
                } else {
                    studentDao.setRfidCardPending(schoolId, student.studentId, uid, now)
                    cards++
                }
            }
            entry.parentPhone?.let(::normalizePhMobile)?.takeIf { it != student.parentPhone }?.let { phone ->
                studentDao.setParentPhonePending(schoolId, student.studentId, phone, now)
                phones++
            }
            entry.face?.let { f -> toPortable(f) }?.let { face ->
                if (faceTemplateRepository.syncState(schoolId, student.studentId)?.first != face.version) {
                    faceTemplateRepository.replaceFace(schoolId, student.studentId, face, FaceTemplateEntity.SYNC_PENDING, source = "backup")
                    faces++
                }
            }
        }
        val result = BackupRestore(cards, faces, phones, missing, conflicts)
        auditLogger.log(
            action = AuditLogger.ACTION_BACKUP_RESTORED,
            details = "$cards cards, $faces faces, $phones numbers; $missing not on this phone, $conflicts card conflicts",
        )
        result
    }

    private fun toPortable(face: BackupFace): PortableFace? {
        if (face.model != FaceTemplateEntity.MODEL_MOBILEFACENET) return null
        val angles = face.angles.map { PortableFaceAngle(it.pose, FaceCodec.mobileFaceNetFromBase64(it.embedding) ?: return null, it.livenessScore) }
        return angles.takeIf { it.isNotEmpty() }?.let { PortableFace(face.model, face.version, it) }
    }
}
