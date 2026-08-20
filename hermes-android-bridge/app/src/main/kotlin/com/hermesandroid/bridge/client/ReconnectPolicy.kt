package com.hermesandroid.bridge.client

/**
 * Reconnect schedule + stability tracking for [RelayClient].
 *
 * Unlike the old budget-based policy (5 attempts then give up), this policy
 * NEVER stops reconnecting: the phone is a monitoring endpoint that must come
 * back by itself after any outage (nightly network drop, server restart...).
 *
 * Backoff is a fixed ascending schedule — 30s → 1m → 2m → 5m → 30m — that
 * loops at the last entry (30m) until a connection sticks. Once a session
 * stays up for at least [stableSessionMs] the counter resets, so the next
 * drop starts over from 30s again.
 *
 * State lives here rather than in the reconnect coroutine on purpose: every
 * failed connect fires another `onFailure`, which schedules another reconnect.
 * A counter local to that coroutine restarts at zero each time, so an
 * unreachable address would skip straight to the 30m cap.
 *
 * All state is guarded by this object's monitor — [RelayClient] touches it from
 * OkHttp callback threads, the reconnect coroutine, and the main thread.
 */
class ReconnectPolicy(
    private val backoffScheduleMs: LongArray = longArrayOf(
        30_000L,          // attempt 1: 30s
        60_000L,          // attempt 2: 1m
        120_000L,         // attempt 3: 2m
        300_000L,         // attempt 4: 5m
        1_800_000L,       // attempt 5+: 30m (loops here)
    ),
    private val stableSessionMs: Long = 60_000L,
) {

    private var attemptCount: Int = 0

    val attempts: Int
        @Synchronized get() = attemptCount

    /** Never exhausted — reconnecting is infinite by design. */
    val isExhausted: Boolean
        @Synchronized get() = false

    val limit: Int
        get() = Int.MAX_VALUE

    /**
     * Consume one attempt and return how long to wait before it.
     */
    @Synchronized
    fun nextBackoffMs(): Long {
        val idx = attemptCount.coerceAtMost(backoffScheduleMs.size - 1)
        attemptCount++
        return backoffScheduleMs[idx]
    }

    /**
     * Report that a connection that had opened is now gone, having lasted
     * [durationMs]. Only a session that proved stable restarts the schedule.
     */
    @Synchronized
    fun onSessionEnded(durationMs: Long) {
        if (durationMs >= stableSessionMs) attemptCount = 0
    }

    @Synchronized
    fun reset() {
        attemptCount = 0
    }
}
