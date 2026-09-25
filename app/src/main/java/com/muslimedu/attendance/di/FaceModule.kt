package com.muslimedu.attendance.di

import com.muslimedu.attendance.face.FaceRecognizer
import com.muslimedu.attendance.face.MobileFaceNetRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Which [FaceRecognizer] Hilt hands out: the MobileFaceNet model (replaced the landmark-ratio placeholder). */
@Module
@InstallIn(SingletonComponent::class)
abstract class FaceModule {

    @Binds
    @Singleton
    abstract fun bindFaceRecognizer(impl: MobileFaceNetRecognizer): FaceRecognizer
}
