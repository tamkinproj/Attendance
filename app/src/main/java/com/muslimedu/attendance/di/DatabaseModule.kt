package com.muslimedu.attendance.di

import android.content.Context
import androidx.room.Room
import com.muslimedu.attendance.data.db.AppDatabase
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13)
            // Still the fallback for installs older than v6, which predate
            // real migrations - see MIGRATION_6_7 for why v6+ is migrated.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideStudentDao(database: AppDatabase): StudentDao = database.studentDao()

    @Provides
    @Singleton
    fun provideAttendanceDao(database: AppDatabase): AttendanceDao = database.attendanceDao()

    @Provides
    @Singleton
    fun provideFaceTemplateDao(database: AppDatabase): FaceTemplateDao = database.faceTemplateDao()

    @Provides
    @Singleton
    fun provideAuditLogDao(database: AppDatabase): AuditLogDao = database.auditLogDao()

    @Provides
    @Singleton
    fun provideGateScanDao(database: AppDatabase): GateScanDao = database.gateScanDao()
}
