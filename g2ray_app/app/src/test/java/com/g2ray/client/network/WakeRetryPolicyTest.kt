package com.g2ray.client.network

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class WakeRetryPolicyTest {
    @Test
    fun earlyRetryKeepsTheFullDelayWithoutAnExplicitMinimumOverride() = runTest {
        var now = 0L
        awaitWakeRetryDelay(2_500L, nowMs = { now }, sleep = { now += it },
            shouldRetryEarly = { true })
        assertEquals(2_500L, now)
    }

    @Test
    fun earlyRetryMinimumCannotBeNegativeOrExceedTheActualWait() = runTest {
        for ((minimum, expected) in listOf(-1L to 0L, 5_000L to 2_500L, Long.MAX_VALUE to 2_500L)) {
            var now = 0L
            awaitWakeRetryDelay(2_500L, nowMs = { now }, sleep = { now += it },
                minimumDelayMs = minimum, shouldRetryEarly = { true })
            assertEquals("minimum=$minimum", expected, now)
        }
    }

    @Test
    fun replacementNetworkCanEndLocalBackoffAtTheNextClockCheck() = runTest {
        var now = 0L
        awaitWakeRetryDelay(60_000L, nowMs = { now }, sleep = { now += it },
            minimumDelayMs = 0L, shouldRetryEarly = { now >= 1_000L })
        assertEquals(1_000L, now)
    }

    @Test
    fun replacementNetworkStillHonorsTheCompleteMandatoryCooldown() = runTest {
        var now = 0L
        awaitWakeRetryDelay(60_000L, nowMs = { now }, sleep = { now += it },
            minimumDelayMs = 5_500L, shouldRetryEarly = { true })
        assertEquals(5_500L, now)
    }

    @Test
    fun unavailableOrUnchangedNetworkDoesNotShortenLocalBackoff() = runTest {
        var now = 0L
        awaitWakeRetryDelay(2_500L, nowMs = { now }, sleep = { now += it },
            minimumDelayMs = 0L, shouldRetryEarly = { false })
        assertEquals(2_500L, now)
    }

    @Test
    fun retryWaitHonorsTheDeadlineWithoutRoundingItsLastInterval() = runTest {
        var nowMs = 0L
        val sleeps = mutableListOf<Long>()
        awaitWakeRetryDelay(2_500L, nowMs = { nowMs }, sleep = {
            sleeps += it
            nowMs += it
        })
        assertEquals(2_500L, nowMs)
        assertEquals(listOf(1_000L, 1_000L, 500L), sleeps)
    }

    @Test
    fun timeInDeepSleepCountsTowardTheRetryWait() = runTest {
        var elapsedMs = 0L
        val sleeps = mutableListOf<Long>()
        awaitWakeRetryDelay(600_000L, nowMs = { elapsedMs }, sleep = {
            sleeps += it
            elapsedMs = 600_000L
        })
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellingTheRetryWaitDoesNotRunItsContinuation() = runTest {
        var continued = false
        val job = launch {
            awaitWakeRetryDelay(600_000L, nowMs = { testScheduler.currentTime })
            continued = true
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(true, job.isCancelled)
        assertEquals(false, continued)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun retryBoundaryConsumesElapsedTimeAndOnlyExtendsForALaterHint() {
        var nowMs = 0L
        val cooldown = WakeRetryCooldown { nowMs }
        assertEquals(null, cooldown.remainingMs())
        cooldown.record(180_000L)
        nowMs = 60_000L
        assertEquals(120_000L, cooldown.remainingMs())
        cooldown.record(90_000L)
        assertEquals(120_000L, cooldown.remainingMs())
        cooldown.record(150_000L)
        assertEquals(150_000L, cooldown.remainingMs())
        nowMs = 220_000L
        assertEquals(0L, cooldown.remainingMs())
    }

    @Test
    fun retryBoundaryRejectsNegativeHintsAndCannotOverflow() {
        var nowMs = 0L
        val cooldown = WakeRetryCooldown { nowMs }
        cooldown.record(-1L)
        assertEquals(null, cooldown.remainingMs())
        cooldown.record(Long.MAX_VALUE)
        assertEquals(MAX_WAKE_RETRY_DELAY_MS, cooldown.remainingMs())
        nowMs = Long.MAX_VALUE - 100L
        cooldown.record(1_000L)
        assertEquals(100L, cooldown.remainingMs())
        nowMs = Long.MAX_VALUE
        assertEquals(0L, cooldown.remainingMs())
    }

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
