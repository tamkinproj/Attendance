package com.muslimedu.attendance.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * `/admin_gate_device_heartbeat`: this gate phone's health, for the web's
 * Gate Devices page - is it online, charged, is the card reader plugged in,
 * are scans stuck waiting to upload, is its clock right. Sent every few
 * minutes while the app runs and from the background sync. Times are
 * ISO-8601 with the phone's offset; [deviceTime] lets the server measure
 * how far off the phone's clock is.
 */
data class GateDeviceHeartbeatRequest(
    @SerializedName("device_uid") val deviceUid: String,
    @SerializedName("model") val model: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("app_version") val appVersion: String,
    @SerializedName("battery_level") val batteryLevel: Int?,
    @SerializedName("charging") val charging: Boolean?,
    @SerializedName("reader_connected") val readerConnected: Boolean,
    @SerializedName("reader_name") val readerName: String?,
    /** "wifi", "cellular", "ethernet" or "other". */
    @SerializedName("network") val network: String?,
    /** Face-confirmed attendance not on the server yet. */
    @SerializedName("pending_uploads") val pendingUploads: Int,
    /** Records the server refused - they need an admin to look (Sync & Account). */
    @SerializedName("failed_uploads") val failedUploads: Int,
    @SerializedName("oldest_pending_at") val oldestPendingAt: String?,
    @SerializedName("last_upload_at") val lastUploadAt: String?,
    @SerializedName("last_sync_ok_at") val lastSyncOkAt: String?,
    @SerializedName("last_scan_at") val lastScanAt: String?,
    @SerializedName("scans_today") val scansToday: Int,
    @SerializedName("rejected_today") val rejectedToday: Int,
    @SerializedName("students") val students: Int,
    @SerializedName("cards") val cards: Int,
    @SerializedName("faces") val faces: Int,
    @SerializedName("schedule_set") val scheduleSet: Boolean,
    @SerializedName("camera_permission") val cameraPermission: Boolean,
    /** The app is on screen (not just the background sync running). */
    @SerializedName("app_open") val appOpen: Boolean,
    @SerializedName("free_storage_mb") val freeStorageMb: Long?,
    @SerializedName("device_time") val deviceTime: String,
)

data class GateDeviceHeartbeatData(
    @SerializedName("device_id") val deviceId: Int?,
    /** The name the admin gave this gate on the web, if any. */
    @SerializedName("name") val name: String?,
    /** This phone's clock minus the server's - positive when the phone is fast. */
    @SerializedName("clock_skew_seconds") val clockSkewSeconds: Int?,
)
