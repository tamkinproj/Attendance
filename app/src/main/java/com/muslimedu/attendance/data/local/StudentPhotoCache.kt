package com.muslimedu.attendance.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches a student's roster photo (a real, absolute URL - see RosterDto.kt -
 * from `/teacher_attendance_roster`'s `photo` field, e.g.
 * `https://manhaje.com/apps/assets/uploads/user-images/xyz.jpg`) to a plain
 * file under this app's private storage the first time it's needed, and
 * never touches the network for it again after that.
 *
 * There is no upload path anywhere in this class, or anywhere else in the
 * app, for a student's photo - it is downloaded once and displayed from the
 * local copy from then on. That's not a policy this class enforces so much
 * as a simple fact about what code exists: nothing here ever POSTs an image
 * anywhere.
 */
@Singleton
class StudentPhotoCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    // Fire-and-forget prefetching (called once per roster row during sync)
    // must not make RosterRepository.syncRoster() wait on 20-40 sequential
    // HTTP round trips - it runs on its own scope instead.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fileFor(schoolId: Int, studentId: Int): File {
        val dir = File(context.filesDir, "student_photos/$schoolId").apply { mkdirs() }
        return File(dir, "$studentId.jpg")
    }

    /** The locally cached file for this student, or null if nothing has been downloaded yet. */
    fun cachedFile(schoolId: Int, studentId: Int): File? =
        fileFor(schoolId, studentId).takeIf { it.exists() && it.length() > 0 }

    /** Loads the cached photo as a [Bitmap], or null if nothing is cached (or it fails to decode). */
    suspend fun loadCachedBitmap(schoolId: Int, studentId: Int): Bitmap? = withContext(Dispatchers.IO) {
        cachedFile(schoolId, studentId)?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    /**
     * Best-effort background download - called once per roster row after a
     * sync. A failed or slow photo fetch must never affect roster syncing
     * itself, which is why this doesn't return anything for the caller to
     * await. Skips the network entirely if a copy is already cached: a
     * school photo changing is rare enough that staying offline-capable is
     * worth the occasional staleness.
     */
    fun prefetch(schoolId: Int, studentId: Int, remoteUrl: String?) {
        if (remoteUrl.isNullOrBlank()) return
        if (cachedFile(schoolId, studentId) != null) return

        scope.launch {
            try {
                download(schoolId, studentId, remoteUrl)
            } catch (e: Exception) {
                // Best-effort - the app works fine with no cached photo, it just
                // falls back to a placeholder wherever one would have shown.
            }
        }
    }

    private fun download(schoolId: Int, studentId: Int, remoteUrl: String) {
        val request = Request.Builder().url(remoteUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body ?: return
            val target = fileFor(schoolId, studentId)
            val tempFile = File(target.parentFile, "${target.name}.tmp")
            tempFile.outputStream().use { out -> body.byteStream().copyTo(out) }
            // Rename only after a fully-written download, so a crash or a
            // killed process mid-download never leaves a truncated file that
            // cachedFile()/loadCachedBitmap() would treat as a valid cache hit.
            tempFile.renameTo(target)
        }
    }
}
