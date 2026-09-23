package com.muslimedu.attendance.di

import com.muslimedu.attendance.face.FaceRecognizer
import com.muslimedu.attendance.face.MlKitFaceRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Missing until now: [FaceRecognizer] is injected (by [MlKitFaceRecognizer]'s
 * only implementation) all over the place, but nothing told Hilt which
 * concrete class to hand out for it - a @Binds-less interface binding always
 * fails at compile time with a Dagger/MissingBinding error, it just never
 * surfaced locally since this sandbox can't run a real compile.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FaceModule {

    @Binds
    @Singleton
    abstract fun bindFaceRecognizer(impl: MlKitFaceRecognizer): FaceRecognizer
}
