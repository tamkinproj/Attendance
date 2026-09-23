package com.muslimedu.attendance.rfid

import android.content.Context
import android.hardware.usb.UsbManager
import com.muslimedu.attendance.BuildConfig

/** The set of readers this build listens to, plus the handles [RfidManager] needs. */
class RfidReaders(
    val reader: RfidReader,
    val keyboard: KeyboardEmulationRfidReader,
    val mock: MockRfidReader?,
)

/**
 * Builds the readers for this build. Both real readers are always active -
 * keyboard-emulation (the common case for cheap USB RFID modules) and raw USB
 * HID/CCID - since either may be what's physically plugged in, and merging them
 * costs nothing when only one is present.
 *
 * Debug builds additionally get a [MockRfidReader], merged in *alongside* the
 * real readers rather than replacing them. It used to replace them, which meant
 * the debug APK - the one actually installed for testing - could never read a
 * real card: scanning a physical card did nothing at all.
 */
object RfidReaderFactory {

    fun create(context: Context, usbManager: UsbManager): RfidReaders {
        val keyboard = KeyboardEmulationRfidReader()
        val usb = UsbHidRfidReader(context.applicationContext, usbManager)
        val mock = if (BuildConfig.DEBUG) MockRfidReader() else null

        return RfidReaders(
            reader = CompositeRfidReader(*listOfNotNull(keyboard, usb, mock).toTypedArray()),
            keyboard = keyboard,
            mock = mock,
        )
    }
}
