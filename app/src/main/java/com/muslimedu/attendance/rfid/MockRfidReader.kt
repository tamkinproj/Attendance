package com.muslimedu.attendance.rfid

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Reader used on debug builds and in the emulator, where no physical USB RFID
 * reader is attached. Emits a card scan whenever [simulateScan] is called,
 * cycling through a small set of sample UIDs by default so the scan screen and
 * student lookup can be exercised end-to-end without hardware.
 */
class MockRfidReader : RfidReader {

    private var listener: ((RfidEvent) -> Unit)? = null
    private var connected = false
    private var cycleIndex = 0

    override fun startScanning(): Flow<RfidEvent> = callbackFlow {
        listener = { event -> trySend(event) }
        connected = true
        send(RfidEvent.Connected)
        awaitClose {
            listener = null
            connected = false
        }
    }

    override fun stopScanning() {
        listener = null
        connected = false
    }

    override fun isConnected(): Boolean = connected

    /** Simulates a card scan with an explicit UID, e.g. from a debug "scan" button. */
    fun simulateScan(uid: String = SAMPLE_UIDS[cycleIndex % SAMPLE_UIDS.size].also { cycleIndex++ }) {
        listener?.invoke(RfidEvent.CardDetected(uid))
    }

    /** Simulates the reader hitting an error (e.g. malformed read). */
    fun simulateError(message: String) {
        listener?.invoke(RfidEvent.Error(message))
    }

    companion object {
        val SAMPLE_UIDS = listOf("04:1A:2B:3C", "04:5D:6E:7F", "09:AA:BB:CC")
    }
}
