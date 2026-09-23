package com.muslimedu.attendance.sync

import java.util.UUID

/**
 * One UUID per attendance record, generated when it's first recorded and
 * sent unchanged on every retry, so a retried submit after a dropped
 * response can't create a duplicate server-side record.
 */
object IdempotencyHandler {
    fun newKey(): String = UUID.randomUUID().toString()
}
