package com.g2ray.client.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeRetryPolicyTest {
    @Test
    fun invalidRequestsAreTerminalButNetworkAndRateLimitFailuresCanRetry() {
        for (code in listOf(400, 401, 402, 403, 404, 405, 410, 415, 422)) {
            assertEquals("HTTP $code", false, wakeHttpFailureRetryAllowed(code))
        }
        for (code in listOf(null, 408, 409, 429, 500, 502, 503)) {
            assertEquals("HTTP $code", true, wakeHttpFailureRetryAllowed(code))
        }
    }

    @Test
    fun whitespaceAndExtremeClockValuesRemainSafe() {
        assertEquals(120_000L, rateLimitRetryAfterMs(" 120 "))
        assertEquals(0L, rateLimitRetryAfterMs(null, "0", Long.MAX_VALUE))
        assertEquals(MAX_WAKE_RETRY_DELAY_MS, rateLimitRetryAfterMs(null, Long.MAX_VALUE.toString(), Long.MIN_VALUE))
    }

    @Test
    fun missingOrMalformedHintsUseTheSecondaryLimitMinimum() {
        assertEquals(60_000L, rateLimitRetryAfterMs(null))
        assertEquals(60_000L, rateLimitRetryAfterMs("invalid", "invalid"))
        assertEquals(60_000L, rateLimitRetryAfterMs("-1", "-1"))
    }

    @Test
    fun resetTimeUsesWallClockMillisecondsAndNeverProducesNegativeDelay() {
        assertEquals(59_500L, rateLimitRetryAfterMs(null, "1060", 1_000_500L))
        assertEquals(0L, rateLimitRetryAfterMs(null, "900", 1_000_500L))
        assertEquals(59_500L, rateLimitRetryAfterMs("30", "1060", 1_000_500L))
    }

    @Test
    fun longerSecondaryCooldownAlsoRemainsBinding() {
        assertEquals(120_000L, rateLimitRetryAfterMs("120", "1060", 1_000_500L))
        assertEquals(59_500L, rateLimitRetryAfterMs("0", "1060", 1_000_500L))
    }

    @Test
    fun extremeHintsCannotOverflowOrDisableRetryIndefinitely() {
        assertEquals(MAX_WAKE_RETRY_DELAY_MS, rateLimitRetryAfterMs(Long.MAX_VALUE.toString()))
        assertEquals(MAX_WAKE_RETRY_DELAY_MS, rateLimitRetryAfterMs(null, Long.MAX_VALUE.toString(), 1_000_500L))
        assertEquals(60_000L, rateLimitRetryAfterMs("99999999999999999999999999999999"))
    }
}
