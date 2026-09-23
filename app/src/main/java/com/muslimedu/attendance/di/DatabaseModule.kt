package com.muslimedu.attendance.di

import android.content.Context
import androidx.room.Room
import com.muslimedu.attendance.data.db.AppDatabase
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
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
            // No real Migration objects yet - the schema is still moving during
            // early development and there's no installed base to preserve.
            // Revisit before any real release.
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
}
