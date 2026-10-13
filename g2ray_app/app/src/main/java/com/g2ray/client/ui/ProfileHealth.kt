package com.g2ray.client.ui

import com.g2ray.client.model.StoredG2RayProfile
import com.g2ray.client.network.RouteMeasurementContext

object ProfileHealth {
    const val DELAY_FRESHNESS_MS = 15 * 60 * 1000L
    const val THROUGHPUT_FRESHNESS_MS = 6 * 60 * 60 * 1000L
    const val UNAVAILABLE_DELETE_FAILURES = 2

    fun displayDelay(
        profile: StoredG2RayProfile,
        current: ProfileDelayState?,
        now: Long = System.currentTimeMillis(),
        networkKey: String? = null
    ): ProfileDelayState {
        val useCurrent = current != null && (current.testing ||
            (current.testedAt == null && current.error != null) ||
            (current.testedAt != null && RouteMeasurementContext.matches(current.networkKey, networkKey) &&
                (!RouteMeasurementContext.matches(profile.lastDelayNetworkKey, networkKey) ||
                    current.testedAt >= (profile.lastDelayTestedAt ?: Long.MIN_VALUE))))
        val result = if (useCurrent) current!! else ProfileDelayState(
            delayMs = profile.lastDelayMs,
            p95Ms = profile.lastDelayP95Ms,
            jitterMs = profile.lastDelayJitterMs,
            successfulSamples = profile.lastDelaySuccessfulSamples,
            sampleCount = profile.lastDelaySampleCount,
            error = profile.lastDelayError,
            testedAt = profile.lastDelayTestedAt,
            networkKey = profile.lastDelayNetworkKey
        )
        if (result.testing || result.testedAt == null ||
            (RouteMeasurementContext.matches(result.networkKey, networkKey) && isDelayFresh(result.testedAt, now))
        ) return result
        return result.copy(
            delayMs = null, p95Ms = null, jitterMs = null,
            successfulSamples = 0, sampleCount = 0, error = "Stale; refresh"
        )
    }

    fun delayLabel(result: ProfileDelayState): String = when {
        result.testing -> "Testing..."
        result.delayMs != null -> buildString {
            append("${result.delayMs} ms")
            if (result.sampleCount == 1) append(" (quick)")
            else result.p95Ms?.let { append(" (p95 ${it} ms)") }
        }
        result.error == "Stale; refresh" -> "Stale; refresh"
        result.error != null -> "Unavailable"
        else -> "Not tested"
    }

    fun isDelayFresh(testedAt: Long?, now: Long = System.currentTimeMillis()): Boolean {
        if (testedAt == null) return false
        val age = now - testedAt
        return age in 0..DELAY_FRESHNESS_MS
    }

    fun isDelayFresh(profile: StoredG2RayProfile, now: Long = System.currentTimeMillis(), networkKey: String? = null): Boolean {
        return RouteMeasurementContext.matches(profile.lastDelayNetworkKey, networkKey) &&
            isDelayFresh(profile.lastDelayTestedAt, now)
    }

    fun isThroughputFresh(profile: StoredG2RayProfile, now: Long = System.currentTimeMillis(), networkKey: String? = null): Boolean {
        val testedAt = profile.lastThroughputTestedAt ?: return false
        val age = now - testedAt
        return RouteMeasurementContext.matches(profile.lastThroughputNetworkKey, networkKey) &&
            age in 0..THROUGHPUT_FRESHNESS_MS &&
            profile.lastDownloadBps?.let { it > 0L } == true
    }

    fun rank(profile: StoredG2RayProfile, now: Long = System.currentTimeMillis(), networkKey: String? = null): Int {
        return when {
            profile.lastDelayTestedAt == null -> 2
            !isDelayFresh(profile, now, networkKey) -> 3
            profile.delayFailureCount > 0 -> 4
            profile.lastDelaySampleCount < 3 ||
                profile.lastDelaySuccessfulSamples * 2 < profile.lastDelaySampleCount -> 1
            else -> 0
        }
    }

    fun isUnavailableForCleanup(profile: StoredG2RayProfile, now: Long = System.currentTimeMillis(), networkKey: String? = null): Boolean {
        return isDelayFresh(profile, now, networkKey) && profile.lastDelayMs == null &&
            profile.lastDelayError != null &&
            profile.delayFailureCount >= UNAVAILABLE_DELETE_FAILURES
    }

    fun delaySortValue(profile: StoredG2RayProfile, now: Long = System.currentTimeMillis(), networkKey: String? = null): Long {
        if (!isDelayFresh(profile, now, networkKey)) return Long.MAX_VALUE
        val median = profile.lastDelayMs?.takeIf { it in 1..9_999 } ?: return Long.MAX_VALUE
        val p95 = profile.lastDelayP95Ms?.takeIf { it in median..60_000L } ?: median
        val tailPenalty = (p95 - median) / 2L
        val jitterPenalty = profile.lastDelayJitterMs?.takeIf { it in 0..60_000L } ?: 0L
        return median + tailPenalty + jitterPenalty
    }
}
