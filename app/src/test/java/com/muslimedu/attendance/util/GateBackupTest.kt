package com.muslimedu.attendance.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GateBackupTest {

    private val backup = GateBackup(
        schoolId = 47,
        createdAt = 1_790_000_000_000,
        students = listOf(
            BackupStudent(
                code = "2026-001", studentId = 12, name = "Malik Aziz", rfidUid = "04:A1:B2:C3", parentPhone = "09171234567",
                face = BackupFace("mobilefacenet", "v-1", listOf(BackupFaceAngle(0, "AACAPw==", 0.9f), BackupFaceAngle(1, "AAAAQA==", 0f))),
            ),
            BackupStudent(code = "2026-002", studentId = 13, name = "Ana Reyes", rfidUid = null, parentPhone = null, face = null),
        ),
    )

    @Test
    fun `a backup comes back exactly with the right password`() {
        val file = GateBackupCodec.encode(backup, "secret1".toCharArray())
        assertEquals(backup, GateBackupCodec.decode(file, "secret1".toCharArray()))
    }

    @Test
    fun `nothing in the file is readable without the password`() {
        val text = String(GateBackupCodec.encode(backup, "secret1".toCharArray()), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("GATEBAK1"))
        listOf("Malik", "0917", "04:A1", "mobilefacenet", "AACAPw").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test
    fun `a wrong password is refused, not misread`() {
        val file = GateBackupCodec.encode(backup, "secret1".toCharArray())
        val error = assertThrows(GateBackupCodec.BackupException::class.java) { GateBackupCodec.decode(file, "secret2".toCharArray()) }
        assertTrue(error.message!!.contains("Wrong password"))
    }

    @Test
    fun `a changed byte is caught`() {
        val file = GateBackupCodec.encode(backup, "secret1".toCharArray())
        file[file.size - 20] = (file[file.size - 20] + 1).toByte()
        assertThrows(GateBackupCodec.BackupException::class.java) { GateBackupCodec.decode(file, "secret1".toCharArray()) }
    }

    @Test
    fun `another file is refused as not a backup`() {
        val error = assertThrows(GateBackupCodec.BackupException::class.java) {
            GateBackupCodec.decode("student,code\\nMalik,2026-001".toByteArray(), "secret1".toCharArray())
        }
        assertTrue(error.message!!.contains("isn't a Gate Attendance backup"))
    }

    @Test
    fun `other encrypted JSON is refused as not a backup`() {
        val file = GateBackupCodec.encrypt("""{"hello":"world"}""".toByteArray(), "secret1".toCharArray())
        assertThrows(GateBackupCodec.BackupException::class.java) { GateBackupCodec.decode(file, "secret1".toCharArray()) }
    }

    @Test
    fun `a backup from a newer app asks for an update`() {
        val file = GateBackupCodec.encode(backup.copy(version = GateBackupCodec.VERSION + 1), "secret1".toCharArray())
        val error = assertThrows(GateBackupCodec.BackupException::class.java) { GateBackupCodec.decode(file, "secret1".toCharArray()) }
        assertTrue(error.message!!.contains("newer app"))
    }

    @Test
    fun `each export is encrypted differently`() {
        val a = GateBackupCodec.encode(backup, "secret1".toCharArray())
        val b = GateBackupCodec.encode(backup, "secret1".toCharArray())
        assertFalse(a.contentEquals(b))
        assertArrayEquals(GateBackupCodec.decrypt(a, "secret1".toCharArray()), GateBackupCodec.decrypt(b, "secret1".toCharArray()))
    }
}
