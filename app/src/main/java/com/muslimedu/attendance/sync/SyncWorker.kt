package com.muslimedu.attendance.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background reliability backstop for [SyncQueueManager]. The opportunistic
 * flush right after a scan handles the common "online right now" case; this
 * periodic job (scheduled from [com.muslimedu.attendance.App]) makes sure
 * anything that missed that - app was killed, was offline, etc. - still
 * gets synced eventually without the app needing to be in the foreground.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncQueueManager: SyncQueueManager,
    private val gateSyncManager: GateSyncManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        gateSyncManager.flush()
        // Classroom attendance is no longer recorded in the app, but rows
        // left over from before gate-only mode still get their chance.
        syncQueueManager.flush()
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val WORK_NAME = "attendance_sync"
    }
}
