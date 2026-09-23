package com.muslimedu.attendance.rfid

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a source of RFID card scans. A single physical reader may be
 * backed by more than one [RfidReader] implementation depending on how it talks
 * to the device (see [RfidReaderFactory]).
 */
interface RfidReader {
    /** Starts producing [RfidEvent]s. Safe to call repeatedly; late collectors replay nothing. */
    fun startScanning(): Flow<RfidEvent>

    /** Releases any held resources (USB interfaces, registered receivers, coroutines). */
    fun stopScanning()

    /** Whether a physical reader is currently attached/ready, where that is knowable. */
    fun isConnected(): Boolean
}

sealed class RfidEvent {
    data class CardDetected(val uid: String, val rawData: ByteArray = ByteArray(0)) : RfidEvent() {
        override fun equals(other: Any?): Boolean = other is CardDetected && other.uid == uid
        override fun hashCode(): Int = uid.hashCode()
    }

    data class Error(val message: String) : RfidEvent()
    data object Connected : RfidEvent()
    data object Disconnected : RfidEvent()
}
