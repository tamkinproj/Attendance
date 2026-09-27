package com.muslimedu.attendance.sync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.muslimedu.attendance.BuildConfig
import com.muslimedu.attendance.data.db.dao.FaceTemplateDao
import com.muslimedu.attendance.data.db.dao.GateScanDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import com.muslimedu.attendance.data.local.DeviceHealthReport
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateDeviceHeartbeatRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.security.TokenManager
import com.muslimedu.attendance.util.DeviceHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed class DeviceHealthResult {
    data object Sent : DeviceHealthResult()
    data object NotSignedIn : DeviceHealthResult()
    /** Reported less than a minute ago - nothing new to say. */
    data object Skipped : DeviceHealthResult()
    data class NotSent(val reason: String) : DeviceHealthResult()
}

/**
 * Tells the school server how this gate phone is doing, for the web's Gate
 * Devices page: battery and charging, whether the card reader is plugged
 * in, scans waiting to upload (and since when), scans refused by the
 * server, today's scan counts, the app version, and the phone's clock (the
 * server compares it with its own - a wrong clock means wrong scan times
 * and Late flags).
 *
 * When: every [PERIOD_MILLIS] while the app process runs, from the
 * background sync ([SyncWorker], every 15 min even with the app closed),
 * on Sync & Account's "Report now", and a few seconds after something an
 * admin would want to see at once - the reader plugged/unplugged, the
 * charger connected/disconnected, the app opened or closed. A phone that
 * stops reporting (off, no internet, app killed) shows as Offline on the
 * web - that is the point.
 *
 * Best-effort and never queued: a missed report is simply replaced by the
 * next one, and a server without the endpoint yet just leaves it unsent.
 */
@Singleton
class DeviceHealthReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val deviceSettings: DeviceSettings,
    private val gateScanDao: GateScanDao,
    private val studentDao: StudentDao,
    private val faceTemplateDao: FaceTemplateDao,
    private val rfidManager: RfidManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastAttemptAt = 0L
    private var started = false
    private var pendingReport: Job? = null

    /** The app is on screen - set by MainActivity. */
    @Volatile
    var appVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            reportSoon()
        }

    /** Starts the periodic report and the event triggers. Idempotent; called from App.onCreate. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { report() }
                delay(PERIOD_MILLIS)
            }
        }
        scope.launch {
            rfidManager.status.map { it.connected }.distinctUntilChanged().drop(1).collect { reportSoon() }
        }
        val power = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = reportSoon()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, power, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * A report in a few seconds - bursts (a USB reader re-enumerating, a
     * screen rotation's stop/start) become one, and the battery state has
     * caught up with a charger change by then.
     */
    fun reportSoon() {
        synchronized(this) {
            pendingReport?.cancel()
            pendingReport = scope.launch {
                delay(EVENT_DELAY_MILLIS)
                runCatching { report(force = true) }
            }
        }
    }

    /** [force]: report even if one went out in the last minute. */
    suspend fun report(force: Boolean = false): DeviceHealthResult = mutex.withLock {
        if (tokenManager.getToken() == null || !deviceSettings.isBound) return@withLock DeviceHealthResult.NotSignedIn
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < MIN_INTERVAL_MILLIS) return@withLock DeviceHealthResult.Skipped
        lastAttemptAt = now

        val previous = deviceSettings.deviceHealth.value
        fun failed(reason: String): DeviceHealthResult {
            deviceSettings.setDeviceHealth(
                DeviceHealthReport(
                    at = now,
                    ok = false,
                    message = reason,
                    webName = previous?.webName,
                    clockSkewSeconds = previous?.clockSkewSeconds,
                    lastOkAt = previous?.lastOkAt,
                ),
            )
            return DeviceHealthResult.NotSent(reason)
        }

        try {
            val response = apiService.adminGateDeviceHeartbeat(snapshot(now))
            if (!response.success) return@withLock failed(response.message ?: "Refused by the server")
            val data = response.data
            deviceSettings.setDeviceHealth(
                DeviceHealthReport(
                    at = now,
                    ok = true,
                    message = null,
                    webName = data?.name,
                    clockSkewSeconds = data?.clockSkewSeconds,
                    lastOkAt = now,
                ),
            )
            DeviceHealthResult.Sent
        } catch (e: HttpException) {
            failed(
                when (e.code()) {
                    401 -> "Session expired - sign out and sign in again"
                    // Route not on the server yet (Laravel's GET-only fallback answers 405).
                    404, 405, 501 -> "Device health isn't set up on the school server yet"
                    else -> e.extractApiErrorMessage() ?: "Server error (${e.code()})"
                },
            )
        } catch (e: IOException) {
            failed("No connection")
        }
    }

    /** Everything the report says, read now. */
    suspend fun snapshot(now: Long = System.currentTimeMillis()): GateDeviceHeartbeatRequest {
        val schoolId = deviceSettings.schoolId.value
        val records = gateScanDao.health(schoolId, LocalDate.now().toString())
        val reader = rfidManager.currentStatus()
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val status = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        return GateDeviceHeartbeatRequest(
            deviceUid = deviceSettings.deviceUid,
            model = listOf(Build.MANUFACTURER, Build.MODEL).distinct().joinToString(" ").trim(),
            androidVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME,
            batteryLevel = level,
            charging = charging,
            readerConnected = reader.connected,
            readerName = reader.deviceName,
            network = networkType(),
            pendingUploads = records.pending,
            failedUploads = records.failed,
            oldestPendingAt = records.oldestPendingAt?.let { DeviceHealth.isoTime(it) },
            lastUploadAt = records.lastUploadAt?.let { DeviceHealth.isoTime(it) },
            lastSyncOkAt = deviceSettings.lastSyncOkAt?.let { DeviceHealth.isoTime(it) },
            lastScanAt = records.lastScanAt?.let { DeviceHealth.isoTime(it) },
            scansToday = records.recordedToday,
            rejectedToday = records.rejectedToday,
            students = studentDao.countForSchool(schoolId),
            cards = studentDao.countWithRfidForSchool(schoolId),
            faces = faceTemplateDao.countActiveForSchool(schoolId),
            scheduleSet = deviceSettings.gateSchedule.value != null,
            cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
            appOpen = appVisible,
            freeStorageMb = runCatching { StatFs(context.filesDir.path).availableBytes / (1024 * 1024) }.getOrNull(),
            deviceTime = DeviceHealth.isoTime(now),
        )
    }

    private fun networkType(): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    companion object {
        /** The web shows a gate as Offline after 20 minutes without a report. */
        const val PERIOD_MILLIS = 5 * 60_000L
        const val MIN_INTERVAL_MILLIS = 60_000L
        const val EVENT_DELAY_MILLIS = 3_000L
    }
}
