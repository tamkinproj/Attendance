package com.muslimedu.attendance.rfid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads cards from readers that expose a real USB interface rather than
 * emulating a keyboard (vendor-specific HID reports, or CCID-class smart card
 * readers). Requires runtime USB permission and claims the device's first
 * interrupt/bulk IN endpoint to poll for report data.
 *
 * The exact byte layout of a card-detected report is vendor-specific; this
 * implementation treats any non-empty read as a UID frame and hex-encodes it,
 * which is correct for the common "send raw UID bytes on card present" class
 * of readers but may need adjusting per the real hardware used in Phase 6
 * (Hardware Integration & Testing).
 */
class UsbHidRfidReader(
    private val context: Context,
    private val usbManager: UsbManager,
) : RfidReader {

    private var connection: UsbDeviceConnection? = null
    private var readJob: Job? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var connected = false
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun startScanning(): Flow<RfidEvent> = callbackFlow {
        val device = findCandidateDevice()
        if (device == null) {
            // Not an error worth showing: the usual setup is a
            // keyboard-emulation reader on the other branch of the composite,
            // or nothing plugged in yet. Surfacing it put a red "Reader Error"
            // card on the scan screen for a perfectly healthy app.
            // RfidManager restarts scanning when a device is attached.
            awaitClose { }
            return@callbackFlow
        }

        if (!usbManager.hasPermission(device)) {
            val granted = requestPermission(device)
            if (!granted) {
                send(RfidEvent.Error("USB permission denied for ${device.deviceName}"))
                awaitClose { }
                return@callbackFlow
            }
        }

        val iface = device.getInterface(0)
        val conn = usbManager.openDevice(device)
        if (conn == null || !conn.claimInterface(iface, true)) {
            send(RfidEvent.Error("Failed to open/claim USB interface"))
            awaitClose { }
            return@callbackFlow
        }
        connection = conn
        connected = true
        send(RfidEvent.Connected)

        val endpoint = (0 until iface.endpointCount)
            .map { iface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }

        if (endpoint == null) {
            send(RfidEvent.Error("No inbound endpoint on USB RFID device"))
        } else {
            readJob = scope.launch { pollEndpoint(conn, endpoint) { event -> trySend(event) } }
        }

        awaitClose {
            readJob?.cancel()
            connection?.close()
            connection = null
            connected = false
        }
    }

    override fun stopScanning() {
        readJob?.cancel()
        connection?.close()
        connection = null
        connected = false
    }

    override fun isConnected(): Boolean = connected

    private fun findCandidateDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { i ->
                val ifaceClass = device.getInterface(i).interfaceClass
                ifaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC || ifaceClass == USB_CLASS_CSCID
            }
        }

    private suspend fun requestPermission(device: UsbDevice): Boolean {
        var granted = false
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                }
            }
        }
        permissionReceiver = receiver
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)

        // Poll briefly for the async permission result rather than blocking indefinitely;
        // the system permission dialog resolves in well under this window in practice.
        var waited = 0
        while (!granted && !usbManager.hasPermission(device) && waited < PERMISSION_TIMEOUT_MS) {
            kotlinx.coroutines.delay(POLL_INTERVAL_MS.toLong())
            waited += POLL_INTERVAL_MS
        }
        context.unregisterReceiver(receiver)
        permissionReceiver = null
        return granted || usbManager.hasPermission(device)
    }

    private suspend fun pollEndpoint(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        onEvent: (RfidEvent) -> Unit,
    ) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(16))
        // The read job's own context, not the reader-wide scope: the scope
        // stays active for the reader's lifetime, so cancelling the job left
        // this loop spinning on a blocking transfer forever.
        while (currentCoroutineContext().isActive) {
            val read = conn.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)
            if (read > 0) {
                val uid = buffer.copyOf(read).joinToString(":") { "%02X".format(it) }
                onEvent(RfidEvent.CardDetected(uid, buffer.copyOf(read)))
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.muslimedu.attendance.rfid.USB_PERMISSION"
        private const val USB_CLASS_CSCID = 0x0B // CCID smart-card class
        private const val READ_TIMEOUT_MS = 1000
        private const val PERMISSION_TIMEOUT_MS = 15_000
        private const val POLL_INTERVAL_MS = 200
    }
}
