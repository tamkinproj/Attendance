package com.muslimedu.attendance.rfid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Combines multiple [RfidReader]s into one stream. Used in release builds to
 * accept scans from both a keyboard-emulation reader and a raw-USB reader at
 * the same time, since either may be what's physically plugged in.
 */
class CompositeRfidReader(private vararg val readers: RfidReader) : RfidReader {

    override fun startScanning(): Flow<RfidEvent> =
        readers.map { it.startScanning() }.merge()

    override fun stopScanning() {
        readers.forEach { it.stopScanning() }
    }

    override fun isConnected(): Boolean = readers.any { it.isConnected() }
}
