package com.muslimedu.attendance.rfid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the UI shows about the reader.
 *
 * [connected] follows what is actually plugged into the USB port rather than
 * whether a scan has arrived, so the indicator turns on the moment a reader is
 * attached and off when it's unplugged - without waiting for a card.
 */
data class ReaderStatus(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val canSimulate: Boolean = false,
)

/**
 * Single entry point the rest of the app talks to for RFID scanning. Owns the
 * readers (see [RfidReaderFactory]), tracks USB attach/detach, and forwards key
 * events from the activity into the keyboard-emulation reader.
 *
 * [events] is a proper multicast [SharedFlow] - safe for any number of
 * independent collectors (the scan screen's view model and the RFID enrollment
 * screen's). This wraps the underlying [RfidReader]'s own `startScanning()`
 * Flow, which each reader implementation still assumes has exactly one
 * collector: it is collected exactly once, internally, and re-broadcast.
 */
@Singleton
class RfidManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val readers = RfidReaderFactory.create(context, usbManager)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<RfidEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RfidEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(ReaderStatus(canSimulate = readers.mock != null))
    val status: StateFlow<ReaderStatus> = _status.asStateFlow()

    /** True on debug builds, where a mock reader is merged in for the "Simulate Scan" button. */
    val canSimulate: Boolean get() = readers.mock != null

    private var scanJob: Job? = null
    private var started = false

    /**
     * Idempotent and safe to call from more than one view model's init block
     * (the scan screen's and the RFID enrollment screen's) - only the first
     * call actually registers anything.
     */
    @Synchronized
    fun register() {
        if (started) return
        started = true

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                @Suppress("DEPRECATION")
                val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null && !device.looksLikeReader()) return
                refreshStatus()
                // The raw-USB reader resolves its device once, when scanning
                // starts, so a reader plugged in after launch is only picked
                // up by starting the readers over.
                restartScanning()
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshStatus()
        restartScanning()
    }

    /** Forward from [android.app.Activity.dispatchKeyEvent]. Returns true if consumed. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean = readers.keyboard.onKeyEvent(event)

    /** Debug-only helper wired to a "Simulate Scan" button; no-op on release builds. */
    fun simulateScan(uid: String? = null) {
        val mock = readers.mock ?: return
        if (uid != null) mock.simulateScan(uid) else mock.simulateScan()
    }

    @Synchronized
    private fun restartScanning() {
        val previous = scanJob
        previous?.cancel()
        scanJob = managerScope.launch {
            // Let the previous collection finish releasing the USB interface
            // before the new one tries to claim it.
            previous?.join()
            readers.reader.startScanning().collect { event -> _events.emit(event) }
        }
    }

    private fun refreshStatus() {
        val device = usbManager.deviceList.values.firstOrNull { it.looksLikeReader() }
        readers.keyboard.setConnected(device != null)
        _status.value = ReaderStatus(
            connected = device != null,
            deviceName = device?.let { it.productName ?: it.deviceName },
            canSimulate = readers.mock != null,
        )
    }
}

/**
 * Whether a USB device plausibly is a card reader: keyboard-emulation readers
 * enumerate as HID, the rest as vendor-specific or CCID smart-card devices.
 * Anything else sharing the port (a hub, a charging accessory) shouldn't light
 * up the reader indicator.
 */
private fun UsbDevice.looksLikeReader(): Boolean =
    (0 until interfaceCount).any { index ->
        when (getInterface(index).interfaceClass) {
            UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_VENDOR_SPEC, USB_CLASS_CCID -> true
            else -> false
        }
    }

private const val USB_CLASS_CCID = 0x0B
