package com.muslimedu.attendance.rfid

import android.content.Context
import android.hardware.usb.UsbManager

/** The set of readers this build listens to, plus the handles [RfidManager] needs. */
class RfidReaders(
    val reader: RfidReader,
    val keyboard: KeyboardEmulationRfidReader,
)

/**
 * Builds the readers for this build. Both real readers are always active -
 * keyboard-emulation (the common case for cheap USB RFID modules) and raw USB
 * HID/CCID - since either may be what's physically plugged in, and merging them
 * costs nothing when only one is present.
 *
 * Only real hardware: there is no simulated reader in any build, so every
 * gate scan comes from a physical card.
 */
object RfidReaderFactory {

    fun create(context: Context, usbManager: UsbManager): RfidReaders {
        val keyboard = KeyboardEmulationRfidReader()
        val usb = UsbHidRfidReader(context.applicationContext, usbManager)

        return RfidReaders(
            reader = CompositeRfidReader(keyboard, usb),
            keyboard = keyboard,
        )
    }
}
