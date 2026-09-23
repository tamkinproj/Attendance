package com.muslimedu.attendance.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.db.entities.AttendanceEntity
import com.muslimedu.attendance.data.db.entities.AuditLogEntity
import com.muslimedu.attendance.data.db.entities.FaceTemplateEntity
import com.muslimedu.attendance.data.db.entities.StudentEntity

@Database(
    entities = [
        StudentEntity::class,
        AttendanceEntity::class,
        FaceTemplateEntity::class,
        AuditLogEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun faceTemplateDao(): FaceTemplateDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        const val DATABASE_NAME = "attendance.db"
    }
}
