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
    version = 10,
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

        /**
         * RFID + face confirmation. Gate rows gain what was checked (card,
         * face, outcome) and a per-row event id; students gain the card
         * registry sync state. Existing gate rows were recorded by the
         * earlier flow, so they keep outcome 'recorded' with rfid_verified 0
         * (not provably card-read) and get a fresh event id each.
         *
         * Also removes the three built-in demo students (codes STU001-3
         * with their made-up card UIDs) if an old build seeded them - no
         * sample data or fake cards in the gate app.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `student_id` INTEGER")
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `section_name` TEXT")
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `rfid_uid` TEXT")
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `rfid_verified` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `outcome` TEXT NOT NULL DEFAULT 'recorded'")
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `failure_reason` TEXT")
                db.execSQL("ALTER TABLE `gate_scans` ADD COLUMN `event_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `gate_scans` SET `event_id` = lower(hex(randomblob(16))) WHERE `event_id` = ''")

                db.execSQL("ALTER TABLE `students` ADD COLUMN `rfid_sync_status` TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("ALTER TABLE `students` ADD COLUMN `rfid_sync_error` TEXT")
                // One stored form for UIDs (see normalizeRfidUid). OR IGNORE:
                // two rows differing only in case would collide on the unique
                // index - leave those as they are rather than fail the upgrade.
                db.execSQL("UPDATE OR IGNORE `students` SET `rfid_card_number` = upper(trim(`rfid_card_number`)) WHERE `rfid_card_number` IS NOT NULL")
                // Cards assigned before the server kept a registry exist only
                // here - queue them for upload once.
                db.execSQL("UPDATE `students` SET `rfid_sync_status` = 'pending' WHERE `rfid_card_number` IS NOT NULL")

                db.execSQL(
                    "DELETE FROM `students` WHERE `is_local_only` = 1 AND `code` IN ('STU001', 'STU002', 'STU003') " +
                        "AND `rfid_card_number` IN ('04:1A:2B:3C', '04:5D:6E:7F', '09:AA:BB:CC')",
                )
            }
        }

        /**
         * Faces enrolled before the MobileFaceNet model are landmark ratios
         * that can't be compared with the model's embeddings. They are kept,
         * marked `landmark`, and ignored by every lookup (FaceTemplateDao),
         * so those students show as "no face" until enrolled again - which
         * replaces the old row.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `model` TEXT NOT NULL DEFAULT 'landmark'")
            }
        }

        /** Parent mobile numbers for the gate texts, with the same upload state as cards. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `students` ADD COLUMN `parent_phone` TEXT")
                db.execSQL("ALTER TABLE `students` ADD COLUMN `has_parent_account` INTEGER")
                db.execSQL("ALTER TABLE `students` ADD COLUMN `phone_sync_status` TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("ALTER TABLE `students` ADD COLUMN `phone_sync_error` TEXT")
            }
        }
    }
}
