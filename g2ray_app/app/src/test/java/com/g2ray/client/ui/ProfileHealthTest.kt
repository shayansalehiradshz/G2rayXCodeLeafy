package com.g2ray.client.ui

import com.g2ray.client.model.StoredG2RayProfile
import com.g2ray.client.model.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHealthTest {
    private val networkKey = "network-a"
    @Test
    fun legacyTimestampWithoutNetworkProvenanceIsNotCurrentPathEvidence() {
        val now = 1_000_000L
        assertFalse(ProfileHealth.isDelayFresh(profile(now, 3, 3).copy(lastDelayNetworkKey = null), now, networkKey))
    }

    @Test
    fun freshFailureDoesNotResurrectStoredSuccessOrAppearStale() {
        val now = 1_000_000L
        val stored = profile(now - 1000, 3, 3)
        val result = ProfileHealth.displayDelay(stored, ProfileDelayState(error = "timeout", testedAt = now, networkKey = networkKey), now, networkKey)
        assertEquals(null, result.delayMs)
        assertEquals(0, result.sampleCount)
        assertEquals("Unavailable", ProfileHealth.delayLabel(result))
    }

    @Test
    fun freshPersistedFailureAppearsUnavailableUntilItActuallyExpires() {
        val now = 1_000_000L
        val stored = profile(now, 0, 1).copy(lastDelayMs = null, lastDelayError = "timeout")
        assertEquals("Unavailable", ProfileHealth.delayLabel(ProfileHealth.displayDelay(stored, null, now, networkKey)))
        assertEquals("Stale; refresh", ProfileHealth.delayLabel(ProfileHealth.displayDelay(
            stored, null, now + ProfileHealth.DELAY_FRESHNESS_MS + 1, networkKey
        )))
    }

    @Test
    fun newSuccessDoesNotInheritAnOldErrorOrSampleCount() {
        val now = 1_000_000L
        val stored = profile(now - 1000, 3, 3).copy(lastDelayError = "old failure")
        val result = ProfileHealth.displayDelay(stored, ProfileDelayState(
            delayMs = 280, testedAt = now, sampleCount = 1, successfulSamples = 1, networkKey = networkKey
        ), now, networkKey)
        assertEquals(null, result.error)
        assertEquals("280 ms (quick)", ProfileHealth.delayLabel(result))
    }

    @Test
    fun delayTailLabelsReflectSuccessfulSampleCount() {
        val small = ProfileDelayState(delayMs = 100, p95Ms = 220, successfulSamples = 3, sampleCount = 5)
        assertEquals("100 ms (worst of 3: 220 ms)", ProfileHealth.delayLabel(small))
        assertEquals("p95 220 ms", ProfileHealth.tailLabel(small.copy(successfulSamples = 20, sampleCount = 20)))
        assertEquals(null, ProfileHealth.tailLabel(small.copy(successfulSamples = 0)))
        assertEquals(null, ProfileHealth.tailLabel(small.copy(successfulSamples = 6)))
        assertEquals(null, ProfileHealth.tailLabel(small.copy(p95Ms = null)))
    }

    @Test
    fun olderUiSnapshotDoesNotOverrideNewerPersistedMeasurement() {
        val now = 1_000_000L
        val result = ProfileHealth.displayDelay(profile(now, 3, 3), ProfileDelayState(
            error = "old failure", testedAt = now - 1, networkKey = networkKey
        ), now, networkKey)
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

        assertEquals(0, ProfileHealth.rank(full, now, networkKey))
        assertEquals(1, ProfileHealth.rank(provisional, now, networkKey))
    }

    @Test
    fun oneSampleQuickScanIsProvisionalEvenWhenItSucceeds() {
        val now = 1_000_000L
        val quick = profile(now - 1_000, successfulSamples = 1, sampleCount = 1)

        assertEquals(1, ProfileHealth.rank(quick, now, networkKey))
    }

    @Test
    fun throughputSamplesExpireAndRejectFutureTimestamps() {
        val now = 10_000_000L
        val fresh = profile(now, successfulSamples = 3, sampleCount = 3).copy(
            lastDownloadBps = 1_000_000L,
            lastThroughputTestedAt = now - ProfileHealth.THROUGHPUT_FRESHNESS_MS,
            lastThroughputNetworkKey = networkKey
        )

        assertTrue(ProfileHealth.isThroughputFresh(fresh, now, networkKey))
        assertFalse(ProfileHealth.isThroughputFresh(
            fresh.copy(lastThroughputTestedAt = now - ProfileHealth.THROUGHPUT_FRESHNESS_MS - 1L),
            now, networkKey
        ))
        assertFalse(ProfileHealth.isThroughputFresh(fresh.copy(lastThroughputTestedAt = now + 1L), now, networkKey))
        assertFalse(ProfileHealth.isThroughputFresh(fresh.copy(lastDownloadBps = null), now, networkKey))
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

        assertFalse(ProfileHealth.isUnavailableForCleanup(oneFailure, now, networkKey))
        assertTrue(ProfileHealth.isUnavailableForCleanup(repeatedFailure, now, networkKey))
        assertFalse(ProfileHealth.isUnavailableForCleanup(healthy, now, networkKey))
    }

    @Test
    fun delaySortValue_penalizesTailLatencyAndJitter() {
        val now = 1_000_000L
        val stable = profile(now, successfulSamples = 3, sampleCount = 3).copy(
            lastDelayP95Ms = 110L,
            lastDelayJitterMs = 5L
        )
        val unstable = stable.copy(lastDelayP95Ms = 400L, lastDelayJitterMs = 80L)

        assertTrue(ProfileHealth.delaySortValue(stable, now, networkKey) < ProfileHealth.delaySortValue(unstable, now, networkKey))
    }

    @Test
    fun anotherNetworksFreshSuccessIsRetainedButNeverRankedOrDisplayedAsCurrent() {
        val now = 1_000_000L
        val stored = profile(now, 3, 3)
        assertTrue(ProfileHealth.isDelayFresh(stored, now, networkKey))
        assertFalse(ProfileHealth.isDelayFresh(stored, now, "network-b"))
        assertFalse(ProfileHealth.isDelayFresh(stored, now, null))
        assertEquals(3, ProfileHealth.rank(stored, now, "network-b"))
        assertEquals(Long.MAX_VALUE, ProfileHealth.delaySortValue(stored, now, "network-b"))
        assertEquals("Stale; refresh", ProfileHealth.delayLabel(ProfileHealth.displayDelay(stored, null, now, "network-b")))
        assertEquals(100L, stored.lastDelayMs)
    }

    @Test
    fun historicalFailuresCannotDeleteAConfigOnAnotherNetworkOrAfterExpiry() {
        val now = 1_000_000L
        val failure = profile(now, 0, 3).copy(lastDelayMs = null, lastDelayError = "timeout", delayFailureCount = 3)
        assertTrue(ProfileHealth.isUnavailableForCleanup(failure, now, networkKey))
        assertFalse(ProfileHealth.isUnavailableForCleanup(failure, now, "network-b"))
        assertFalse(ProfileHealth.isUnavailableForCleanup(failure, now + ProfileHealth.DELAY_FRESHNESS_MS + 1L, networkKey))
        assertEquals(3, ProfileHealth.rank(failure, now, "network-b"))
    }

    @Test
    fun throughputNeedsItsOwnMatchingNetworkEvenAfterAFreshDelayMeasurement() {
        val now = 1_000_000L
        val stored = profile(now, 3, 3).copy(
            lastDownloadBps = 1_000_000L, lastThroughputTestedAt = now,
            lastDelayNetworkKey = "network-b", lastThroughputNetworkKey = networkKey
        )
        assertTrue(ProfileHealth.isDelayFresh(stored, now, "network-b"))
        assertFalse(ProfileHealth.isThroughputFresh(stored, now, "network-b"))
        assertTrue(ProfileHealth.isThroughputFresh(stored, now, networkKey))
    }

    @Test
    fun matchingUiResultWinsOverAnotherNetworkRegardlessOfTheOldTimestamp() {
        val now = 1_000_000L
        val stored = profile(now, 3, 3)
        val current = ProfileDelayState(delayMs = 250L, testedAt = now - 1L, networkKey = "network-b")
        assertEquals(250L, ProfileHealth.displayDelay(stored, current, now, "network-b").delayMs)
        assertEquals(100L, ProfileHealth.displayDelay(stored, current, now, networkKey).delayMs)
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
        lastDelaySampleCount = sampleCount,
        lastDelayNetworkKey = networkKey
    )
}
