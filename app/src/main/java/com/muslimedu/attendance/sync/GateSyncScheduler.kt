package com.muslimedu.attendance.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Sync as soon as the connection is back." Queues a one-off [SyncWorker]
 * that WorkManager holds until the device has a network - immediately if it
 * already has one - so records saved offline go up the moment the gate is
 * back online, not at the next 15-minute periodic run. Survives the app
 * being closed or the device restarting.
 *
 * KEEP: one queued run covers every record saved before it starts; a record
 * saved while it runs is picked up by the upload that follows every scan.
 */
@Singleton
class GateSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun syncWhenOnline() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "gate_sync_when_online"
    }
}
