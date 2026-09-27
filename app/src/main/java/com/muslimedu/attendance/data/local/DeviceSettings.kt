package com.muslimedu.attendance.data.local

import android.content.Context
import com.muslimedu.attendance.data.repository.GateSchedule
import com.muslimedu.attendance.data.repository.GateScheduleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which school this device belongs to, persisted across restarts and
 * independent of whether anyone is logged in - the gate app works fully
 * offline with no login, so every local row (students, gate scans, face
 * templates, audit entries) is scoped by this instead of the session.
 *
 * Starts [UNBOUND_SCHOOL_ID] on a fresh install. The first successful admin
 * login binds it (see [com.muslimedu.attendance.data.repository.DeviceBindingRepository]),
 * moving anything recorded before that onto the real school id. Once bound
 * it never changes - a login from a different school is refused, so one
 * school's offline scans can never be uploaded into another's.
 */
@Singleton
class DeviceSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private val _schoolId = MutableStateFlow(prefs.getInt(KEY_SCHOOL_ID, UNBOUND_SCHOOL_ID))
    val schoolId: StateFlow<Int> = _schoolId.asStateFlow()

    val isBound: Boolean get() = _schoolId.value != UNBOUND_SCHOOL_ID

    fun bindSchool(schoolId: Int) {
        prefs.edit().putInt(KEY_SCHOOL_ID, schoolId).apply()
        _schoolId.value = schoolId
    }

    /**
     * True from a fresh admin sign-in until the sync step after it finishes
     * (or is skipped). Persisted, so an app killed mid-sync resumes on the
     * sync step instead of skipping straight to the gate.
     */
    private val _postLoginSyncPending = MutableStateFlow(prefs.getBoolean(KEY_POST_LOGIN_SYNC, false))
    val postLoginSyncPending: StateFlow<Boolean> = _postLoginSyncPending.asStateFlow()

    fun setPostLoginSyncPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_POST_LOGIN_SYNC, pending).apply()
        _postLoginSyncPending.value = pending
    }

    /**
     * The admin's gate schedule: Coming In and Going Out scans per student per
     * day (the same number each way) and when each one opens. Null until set -
     * the gate can't be used before that. See [GateSchedule].
     */
    private val _gateSchedule = MutableStateFlow(readGateSchedule())
    val gateSchedule: StateFlow<GateScheduleConfig?> = _gateSchedule.asStateFlow()

    fun setGateSchedule(config: GateScheduleConfig) {
        prefs.edit()
            .putInt(KEY_GATE_SCANS_PER_DAY, config.perDay)
            .putString(KEY_GATE_IN_TIMES, config.inTimes.joinToString(",") { it.format(HH_MM) })
            .putString(KEY_GATE_OUT_TIMES, config.outTimes.joinToString(",") { it.format(HH_MM) })
            .putString(KEY_GATE_LATE_AFTER, config.lateAfter.joinToString(",") { it?.format(HH_MM) ?: NO_LATE_CHECK })
            .apply()
        _gateSchedule.value = config
    }

    /** Times missing or unreadable (a schedule saved before they existed) fall back to the defaults for that count. */
    private fun readGateSchedule(): GateScheduleConfig? {
        val perDay = prefs.getInt(KEY_GATE_SCANS_PER_DAY, 0).takeIf { it > 0 } ?: return null
        fun times(key: String) = prefs.getString(key, null)?.split(',')
            ?.mapNotNull { runCatching { LocalTime.parse(it.trim(), HH_MM) }.getOrNull() }
        val inTimes = times(KEY_GATE_IN_TIMES)
        val outTimes = times(KEY_GATE_OUT_TIMES)
        if (inTimes != null && outTimes != null && inTimes.size == perDay && GateSchedule.timesInOrder(inTimes, outTimes)) {
            return GateScheduleConfig(perDay, inTimes, outTimes, readLateAfter(inTimes, outTimes))
        }
        val (defaultIn, defaultOut) = GateSchedule.defaultTimes(perDay)
        return GateScheduleConfig(perDay, defaultIn, defaultOut)
    }

    /**
     * No stored late times (a schedule saved before late checks existed) means
     * no late check at all - never a default the admin didn't choose. One that
     * no longer fits its Coming In is dropped the same way.
     */
    private fun readLateAfter(inTimes: List<LocalTime>, outTimes: List<LocalTime>): List<LocalTime?> {
        val stored = prefs.getString(KEY_GATE_LATE_AFTER, null)?.split(',')
            ?.map { part -> part.trim().takeIf { it != NO_LATE_CHECK }?.let { runCatching { LocalTime.parse(it, HH_MM) }.getOrNull() } }
        if (stored == null || stored.size != inTimes.size) return List(inTimes.size) { null }
        return stored.mapIndexed { i, time -> time?.takeIf { it >= inTimes[i] && it < outTimes[i] } }
    }

    /**
     * A random id made once per install - how the web's Gate Devices page
     * tells this phone from the school's other gates. A reinstall makes a
     * new one; the server then takes over this model's stale row (see
     * GateDevice::findForHeartbeat), so the gate keeps its name there.
     */
    val deviceUid: String by lazy {
        prefs.getString(KEY_DEVICE_UID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_UID, it).apply()
        }
    }

    /** The server time of the last complete shared-face download - the next one asks only for newer faces. */
    var faceDownloadSince: String?
        get() = prefs.getString(KEY_FACE_DOWNLOAD_SINCE, null)
        set(value) {
            prefs.edit().putString(KEY_FACE_DOWNLOAD_SINCE, value).apply()
        }

    /** When an upload last finished with nothing left waiting - reported to the web as "last synced". */
    var lastSyncOkAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC_OK, 0L).takeIf { it > 0L }
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_OK, value ?: 0L).apply()
        }

    private val _deviceHealth = MutableStateFlow(readDeviceHealth())

    /** The last device-health report to the server (null: never tried). */
    val deviceHealth: StateFlow<DeviceHealthReport?> = _deviceHealth.asStateFlow()

    fun setDeviceHealth(report: DeviceHealthReport) {
        prefs.edit()
            .putLong(KEY_HEALTH_AT, report.at)
            .putBoolean(KEY_HEALTH_OK, report.ok)
            .putString(KEY_HEALTH_MESSAGE, report.message)
            .putString(KEY_HEALTH_WEB_NAME, report.webName)
            .putInt(KEY_HEALTH_CLOCK_SKEW, report.clockSkewSeconds ?: NO_CLOCK_SKEW)
            .putLong(KEY_HEALTH_LAST_OK_AT, report.lastOkAt ?: 0L)
            .apply()
        _deviceHealth.value = report
    }

    private fun readDeviceHealth(): DeviceHealthReport? {
        val at = prefs.getLong(KEY_HEALTH_AT, 0L).takeIf { it > 0L } ?: return null
        return DeviceHealthReport(
            at = at,
            ok = prefs.getBoolean(KEY_HEALTH_OK, false),
            message = prefs.getString(KEY_HEALTH_MESSAGE, null),
            webName = prefs.getString(KEY_HEALTH_WEB_NAME, null),
            clockSkewSeconds = prefs.getInt(KEY_HEALTH_CLOCK_SKEW, NO_CLOCK_SKEW).takeIf { it != NO_CLOCK_SKEW },
            lastOkAt = prefs.getLong(KEY_HEALTH_LAST_OK_AT, 0L).takeIf { it > 0L },
        )
    }

    var lastStudentDownloadAt: Long?
        get() = prefs.getLong(KEY_LAST_STUDENT_DOWNLOAD, 0L).takeIf { it > 0L }
        set(value) {
            prefs.edit().putLong(KEY_LAST_STUDENT_DOWNLOAD, value ?: 0L).apply()
        }

    companion object {
        const val UNBOUND_SCHOOL_ID = 0
        private const val PREFS_FILE_NAME = "device_settings"
        private const val KEY_SCHOOL_ID = "school_id"
        private const val KEY_LAST_STUDENT_DOWNLOAD = "last_student_download_at"
        private const val KEY_POST_LOGIN_SYNC = "post_login_sync_pending"
        private const val KEY_GATE_SCANS_PER_DAY = "gate_scans_per_day"
        private const val KEY_GATE_IN_TIMES = "gate_in_times"
        private const val KEY_GATE_OUT_TIMES = "gate_out_times"
        private const val KEY_GATE_LATE_AFTER = "gate_late_after"
        private const val NO_LATE_CHECK = "-"
        private const val KEY_DEVICE_UID = "device_uid"
        private const val KEY_LAST_SYNC_OK = "last_sync_ok_at"
        private const val KEY_FACE_DOWNLOAD_SINCE = "face_download_since"
        private const val KEY_HEALTH_AT = "health_at"
        private const val KEY_HEALTH_OK = "health_ok"
        private const val KEY_HEALTH_MESSAGE = "health_message"
        private const val KEY_HEALTH_WEB_NAME = "health_web_name"
        private const val KEY_HEALTH_CLOCK_SKEW = "health_clock_skew"
        private const val KEY_HEALTH_LAST_OK_AT = "health_last_ok_at"
        private const val NO_CLOCK_SKEW = Int.MIN_VALUE
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * The last device-health report ([com.muslimedu.attendance.sync.DeviceHealthReporter]).
 * [ok] false: it didn't reach the server - [message] says why, and
 * [webName] / [clockSkewSeconds] are from the last one that did ([lastOkAt]).
 */
data class DeviceHealthReport(
    val at: Long,
    val ok: Boolean,
    val message: String?,
    /** What the web's Gate Devices page calls this phone (the admin names it there). */
    val webName: String?,
    /** This phone's clock minus the server's, in seconds - positive when the phone is fast. */
    val clockSkewSeconds: Int?,
    val lastOkAt: Long?,
)
