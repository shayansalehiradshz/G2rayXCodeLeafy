package com.g2ray.client.network

import kotlinx.coroutines.delay

internal const val MAX_WAKE_RETRY_DELAY_MS = 24 * 60 * 60 * 1000L

internal fun wakeHttpFailureRetryAllowed(httpCode: Int?): Boolean =
    httpCode !in setOf(400, 401, 402, 403, 404, 405, 410, 415, 422)

/** Keep a retry boundary across fallbacks and deadline cancellation. */
internal class WakeRetryCooldown(private val nowMs: () -> Long) {
    private var deadlineMs: Long? = null

    fun record(retryAfterMs: Long?) {
        val delayMs = retryAfterMs?.takeIf { it >= 0L }
            ?.coerceAtMost(MAX_WAKE_RETRY_DELAY_MS) ?: return
        val now = nowMs().coerceAtLeast(0L)
        val candidate = now.coerceAtMost(Long.MAX_VALUE - delayMs) + delayMs
        deadlineMs = maxOf(deadlineMs ?: 0L, candidate)
    }

    fun remainingMs(): Long? = deadlineMs?.let {
        (it - nowMs().coerceAtLeast(0L)).coerceIn(0L, MAX_WAKE_RETRY_DELAY_MS)
    }
}

/** Coroutine timers pause in deep sleep; recheck Android's elapsed clock. */
internal suspend fun awaitWakeRetryDelay(
    delayMs: Long,
    nowMs: () -> Long,
    sleep: suspend (Long) -> Unit = { delay(it) },
    minimumDelayMs: Long = delayMs,
    shouldRetryEarly: () -> Boolean = { false }
) {
    val durationMs = delayMs.coerceAtLeast(0L)
    val startedAtMs = nowMs().coerceIn(0L, Long.MAX_VALUE - durationMs)
    val deadlineMs = startedAtMs + durationMs
    val minimumDeadlineMs = startedAtMs + minimumDelayMs.coerceIn(0L, durationMs)
    while (true) {
        val now = nowMs().coerceAtLeast(0L)
        val remainingMs = deadlineMs - now
        if (remainingMs <= 0L) return
        // A new physical path may supersede local exponential backoff, but
        // never a server's mandatory Retry-After boundary. Other callers keep
        // the complete delay unless they explicitly opt into early retry.
        if (now >= minimumDeadlineMs && shouldRetryEarly()) return
        // These timers do not wake the CPU. A resumed device checks its
        // remaining cooldown within a second instead of restarting the wait.
        val untilMinimumMs = (minimumDeadlineMs - now).takeIf { it > 0L } ?: 1_000L
        sleep(minOf(remainingMs, untilMinimumMs, 1_000L))
    }
}

/** GitHub specifies seconds for Retry-After and UTC epoch seconds for reset. */
internal fun rateLimitRetryAfterMs(
    retryAfterSeconds: String?,
    resetEpochSeconds: String? = null,
    nowEpochMs: Long = System.currentTimeMillis()
): Long {
    val maxWaitMs = MAX_WAKE_RETRY_DELAY_MS
    val retrySeconds = retryAfterSeconds?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    val retryDelayMs = retrySeconds?.coerceAtMost(maxWaitMs / 1000L)?.times(1000L)
    val resetSeconds = resetEpochSeconds?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    val resetDelayMs = resetSeconds?.let {
        val resetMs = resetSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        (resetMs - nowEpochMs.coerceAtLeast(0L)).coerceIn(0L, maxWaitMs)
    }
    if (retryDelayMs != null || resetDelayMs != null) {
        // A secondary cooldown must not bypass an exhausted primary limit.
        return maxOf(retryDelayMs ?: 0L, resetDelayMs ?: 0L)
    }
    // A secondary limit without a usable hint requires at least a minute.
    return 60_000L
}
