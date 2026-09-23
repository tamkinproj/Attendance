package com.muslimedu.attendance.sync

/** Exponential backoff schedule for attendance sync retries, per the spec. */
object RetryStrategy {
    private val DELAYS_SECONDS = listOf(1, 2, 4, 8, 16, 32, 60, 120)
    const val MAX_ATTEMPTS = 8

    /** [attemptNumber] is the retry count *before* this attempt (0 for the first retry). */
    fun nextRetryAt(attemptNumber: Int, now: Long = System.currentTimeMillis()): Long {
        val delaySeconds = DELAYS_SECONDS.getOrElse(attemptNumber) { DELAYS_SECONDS.last() }
        return now + delaySeconds * 1000L
    }
}
