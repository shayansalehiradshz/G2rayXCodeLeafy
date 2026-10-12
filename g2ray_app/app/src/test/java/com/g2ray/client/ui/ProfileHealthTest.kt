package com.g2ray.client.ui

import com.g2ray.client.model.StoredG2RayProfile
import com.g2ray.client.model.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHealthTest {
    @Test
    fun freshFailureDoesNotResurrectStoredSuccessOrAppearStale() {
        val now = 1_000_000L
        val stored = profile(now - 1000, 3, 3)
        val result = ProfileHealth.displayDelay(stored, ProfileDelayState(error = "timeout", testedAt = now), now)
        assertEquals(null, result.delayMs)
        assertEquals(0, result.sampleCount)
        assertEquals("Unavailable", ProfileHealth.delayLabel(result))
    }

    @Test
    fun freshPersistedFailureAppearsUnavailableUntilItActuallyExpires() {
        val now = 1_000_000L
        val stored = profile(now, 0, 1).copy(lastDelayMs = null, lastDelayError = "timeout")
        assertEquals("Unavailable", ProfileHealth.delayLabel(ProfileHealth.displayDelay(stored, null, now)))
        assertEquals("Stale; refresh", ProfileHealth.delayLabel(ProfileHealth.displayDelay(
            stored, null, now + ProfileHealth.DELAY_FRESHNESS_MS + 1
        )))
    }

    @Test
    fun newSuccessDoesNotInheritAnOldErrorOrSampleCount() {
        val now = 1_000_000L
        val stored = profile(now - 1000, 3, 3).copy(lastDelayError = "old failure")
        val result = ProfileHealth.displayDelay(stored, ProfileDelayState(
            delayMs = 280, testedAt = now, sampleCount = 1, successfulSamples = 1
        ), now)
        assertEquals(null, result.error)
        assertEquals("280 ms (quick)", ProfileHealth.delayLabel(result))
    }

    @Test
    fun olderUiSnapshotDoesNotOverrideNewerPersistedMeasurement() {
        val now = 1_000_000L
        val result = ProfileHealth.displayDelay(profile(now, 3, 3), ProfileDelayState(
            error = "old failure", testedAt = now - 1
        ), now)
        assertEquals(100L, result.delayMs)
        assertEquals(null, result.error)
    }

    @Test
    fun untestedAndInProgressRemainDistinct() {
        val now = 1_000_000L
        val stored = profile(now, 0, 0).copy(lastDelayMs = null, lastDelayTestedAt = null)
        assertEquals("Not tested", ProfileHealth.delayLabel(ProfileHealth.displayDelay(stored, null, now)))
        assertEquals("Testing...", ProfileHealth.delayLabel(ProfileHealth.displayDelay(
            stored, ProfileDelayState(testing = true), now
        )))
    }

    @Test
    fun delayResultsExpireAfterTheFreshnessWindow() {
        val now = 1_000_000L

        assertTrue(ProfileHealth.isDelayFresh(now - ProfileHealth.DELAY_FRESHNESS_MS, now))
        assertFalse(ProfileHealth.isDelayFresh(now - ProfileHealth.DELAY_FRESHNESS_MS - 1, now))
        assertFalse(ProfileHealth.isDelayFresh(null, now))
    }

    @Test
    fun lowSampleResultsRankBelowFullyMeasuredFreshResults() {
        val now = 1_000_000L
        val full = profile(now - 1_000, successfulSamples = 3, sampleCount = 3)
        val provisional = profile(now - 1_000, successfulSamples = 1, sampleCount = 3)

        assertEquals(0, ProfileHealth.rank(full, now))
        assertEquals(1, ProfileHealth.rank(provisional, now))
    }

    @Test
    fun oneSampleQuickScanIsProvisionalEvenWhenItSucceeds() {
        val now = 1_000_000L
        val quick = profile(now - 1_000, successfulSamples = 1, sampleCount = 1)

        assertEquals(1, ProfileHealth.rank(quick, now))
    }

    @Test
    fun throughputSamplesExpireAndRejectFutureTimestamps() {
        val now = 10_000_000L
        val fresh = profile(now, successfulSamples = 3, sampleCount = 3).copy(
            lastDownloadBps = 1_000_000L,
            lastThroughputTestedAt = now - ProfileHealth.THROUGHPUT_FRESHNESS_MS
        )

        assertTrue(ProfileHealth.isThroughputFresh(fresh, now))
        assertFalse(ProfileHealth.isThroughputFresh(
            fresh.copy(lastThroughputTestedAt = now - ProfileHealth.THROUGHPUT_FRESHNESS_MS - 1L),
            now
        ))
        assertFalse(ProfileHealth.isThroughputFresh(fresh.copy(lastThroughputTestedAt = now + 1L), now))
        assertFalse(ProfileHealth.isThroughputFresh(fresh.copy(lastDownloadBps = null), now))
    }

    @Test
    fun cleanupRequiresRepeatedFailureAndNeverDeletesAHealthyProfile() {
        val now = 1_000_000L
        val oneFailure = profile(now, successfulSamples = 0, sampleCount = 1).copy(
            lastDelayMs = null,
            lastDelayError = "timeout",
            delayFailureCount = 1
        )
        val repeatedFailure = oneFailure.copy(delayFailureCount = ProfileHealth.UNAVAILABLE_DELETE_FAILURES)
        val healthy = profile(now, successfulSamples = 3, sampleCount = 3)

        assertFalse(ProfileHealth.isUnavailableForCleanup(oneFailure))
        assertTrue(ProfileHealth.isUnavailableForCleanup(repeatedFailure))
        assertFalse(ProfileHealth.isUnavailableForCleanup(healthy))
    }

    @Test
    fun delaySortValue_penalizesTailLatencyAndJitter() {
        val now = 1_000_000L
        val stable = profile(now, successfulSamples = 3, sampleCount = 3).copy(
            lastDelayP95Ms = 110L,
            lastDelayJitterMs = 5L
        )
        val unstable = stable.copy(lastDelayP95Ms = 400L, lastDelayJitterMs = 80L)

        assertTrue(ProfileHealth.delaySortValue(stable, now) < ProfileHealth.delaySortValue(unstable, now))
    }

    private fun profile(testedAt: Long, successfulSamples: Int, sampleCount: Int) = StoredG2RayProfile(
        id = "profile",
        accountId = "account",
        displayName = "Profile",
        rawUri = "vless://profile",
        transport = Transport.XHTTP,
        address = "example.com",
        port = 443,
        sni = "example.com",
        hostHeader = "example.com",
        path = "/",
        isDefault = true,
        createdAt = 1L,
        updatedAt = 1L,
        lastDelayMs = 100L,
        lastDelayTestedAt = testedAt,
        lastDelaySuccessfulSamples = successfulSamples,
        lastDelaySampleCount = sampleCount
    )
}
