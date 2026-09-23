package com.muslimedu.attendance.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.AttendanceEntity
import com.muslimedu.attendance.data.db.entities.AuditLogEntity
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity
import com.muslimedu.attendance.data.db.entities.GateScanEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity

@Database(
    entities = [
        StudentEntity::class,
        AttendanceEntity::class,
        FaceTemplateEntity::class,
        AuditLogEntity::class,
        GateScanEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun faceTemplateDao(): FaceTemplateDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun gateScanDao(): GateScanDao

    companion object {
        const val DATABASE_NAME = "attendance.db"

        /**
         * A real migration, not the destructive fallback: an installed
         * device already holds card assignments and face templates that
         * exist nowhere else (neither is ever uploaded), so wiping the
         * database to add one table would silently lose them. The SQL must
         * match [GateScanEntity] exactly or Room rejects it at open time.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gate_scans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`school_id` INTEGER NOT NULL, " +
                        "`student_code` TEXT NOT NULL, " +
                        "`student_name` TEXT, " +
                        "`direction` TEXT NOT NULL, " +
                        "`scan_date` TEXT NOT NULL, " +
                        "`scan_time` TEXT NOT NULL, " +
                        "`scanned_at` INTEGER NOT NULL, " +
                        "`verified_by_face` INTEGER NOT NULL, " +
                        "`face_match_score` REAL, " +
                        "`sync_status` TEXT NOT NULL, " +
                        "`sync_attempts` INTEGER NOT NULL, " +
                        "`next_retry_at` INTEGER, " +
                        "`last_sync_at` INTEGER, " +
                        "`server_attendance_id` INTEGER, " +
                        "`error_message` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gate_scans_sync_status` ON `gate_scans` (`sync_status`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_gate_scans_school_id_scan_date` ON `gate_scans` (`school_id`, `scan_date`)",
                )
            }
        }
    }
}
