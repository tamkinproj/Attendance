package com.muslimedu.attendance.rfid

import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Most inexpensive 13.56MHz USB RFID readers (the ones sold as plug-and-play
 * "USB RFID reader" modules) do not expose a custom USB protocol at all: they
 * enumerate as a standard USB HID keyboard and "type" the card UID as digits
 * followed by Enter. Android delivers that input as ordinary [KeyEvent]s to
 * whichever view has focus - no [android.hardware.usb.UsbManager] permission
 * or raw USB transfer is involved.
 *
 * This reader has no USB API of its own: [MainActivity] forwards every
 * [KeyEvent] it receives via [onKeyEvent], and this class buffers the typed
 * characters until a terminator, then emits a [RfidEvent.CardDetected].
 * [isConnected] cannot observe a
 * "connection" the way a real USB API can (the OS treats it as a keyboard, not
 * a device this app owns) - [RfidManager] drives it from the USB attach/detach
 * broadcasts for devices that match the HID keyboard class.
 */
class KeyboardEmulationRfidReader : RfidReader {

    private val buffer = StringBuilder()
    private var lastKeyAt = 0L
    private var listener: ((RfidEvent) -> Unit)? = null
    private var connected = false

    override fun startScanning(): Flow<RfidEvent> = callbackFlow {
        listener = { event -> trySend(event) }
        awaitClose { listener = null }
    }

    override fun stopScanning() {
        listener = null
        buffer.clear()
    }

    override fun isConnected(): Boolean = connected

    fun setConnected(isConnected: Boolean) {
        connected = isConnected
        if (!isConnected) buffer.clear()
    }

    /**
     * Call from [android.app.Activity.dispatchKeyEvent] with every key event the
     * activity receives. Returns true if the event was consumed as reader input,
     * false if the activity should handle it normally.
     *
     * Accepts letters as well as digits: plenty of readers type the UID as hex
     * (`04A1B2C3`) rather than decimal, and only accepting digits meant those
     * cards produced a buffer that never completed and so scanned as nothing at
     * all. Enter and Tab both terminate, since readers differ on which they
     * send.
     *
     * A card is "typed" in one fast burst, so a gap longer than
     * [INTER_KEY_TIMEOUT_MS] starts a new UID. Without that, a partial read
     * (reader unplugged mid-scan, a UID with no terminator) would sit in the
     * buffer forever and get glued onto the front of the next card.
     */
    @Synchronized
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // A reader is a physical USB keyboard. Keys from the on-screen
        // keyboard are the user typing - Gboard's number pad sends every
        // digit as a key event, and eating those made the PIN field
        // impossible to type in.
        if (isSoftKeyboardInput(event.deviceId, event.flags, event.device?.isVirtual)) return false

        if (event.eventTime - lastKeyAt > INTER_KEY_TIMEOUT_MS) buffer.setLength(0)

        val isTerminator = event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_TAB
        if (isTerminator) {
            val uid = buffer.toString()
            buffer.setLength(0)
            if (uid.length < MIN_UID_LENGTH) return false
            listener?.invoke(RfidEvent.CardDetected(uid))
            return true
        }

        val char = event.unicodeChar.toChar()
        if (!char.isLetterOrDigit()) return false
        buffer.append(char)
        lastKeyAt = event.eventTime
        return true
    }

    companion object {
        /**
         * True for keys from the on-screen keyboard (or any other virtual
         * input device) - never card reader input. [deviceIsVirtual] is
         * `InputDevice.isVirtual`, null when the device is unknown.
         */
        internal fun isSoftKeyboardInput(deviceId: Int, flags: Int, deviceIsVirtual: Boolean?): Boolean =
            deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD ||
                (flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0 ||
                deviceIsVirtual == true

        private const val INTER_KEY_TIMEOUT_MS = 500L

        /** Short bursts are stray keystrokes, not a card. */
        private const val MIN_UID_LENGTH = 4
    }
}
