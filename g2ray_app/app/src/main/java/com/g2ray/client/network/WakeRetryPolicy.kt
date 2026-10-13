package com.g2ray.client.network

internal const val MAX_WAKE_RETRY_DELAY_MS = 24 * 60 * 60 * 1000L

internal fun wakeHttpFailureRetryAllowed(httpCode: Int?): Boolean =
    httpCode !in setOf(400, 401, 402, 403, 404, 405, 410, 415, 422)

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
