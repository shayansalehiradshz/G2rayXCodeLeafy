package com.g2ray.client.vpn

import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.TunnelTrafficSource
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSpeedTest {
    @Test
    fun nativeUidTrafficSourceReturnsPhysicalCountersAndRejectsUnsupportedValues() {
        var rx = 10_000L
        var tx = 5_000L
        val source = AndroidUidTrafficByteSource(
            uid = 1234,
            readRxBytes = { rx },
            readTxBytes = { tx }
        )

        assertEquals(TrafficBytes(10_000L, 5_000L), source.snapshot())
        rx = -1L
        assertEquals(null, source.snapshot())
    }

    @Test
    fun sample_firstSampleReportsTotalsAndZeroSpeed() {
        val source = FakeTrafficByteSource(rx = 1000, tx = 500)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        val speed = sampler.sample()

        assertTrue(speed.sampleValid)
        assertEquals(0, speed.rxBps)
        assertEquals(0, speed.txBps)
        assertEquals(0, speed.totalRxBytes)
        assertEquals(0, speed.totalTxBytes)
    }

    @Test
    fun sample_secondSampleComputesBytesPerSecond() {
        val source = FakeTrafficByteSource(rx = 1000, tx = 500)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        sampler.sample()
        source.rx = 3000
        source.tx = 1500
        now = 2000L
        val speed = sampler.sample()

        assertEquals(2000, speed.rxBps)
        assertEquals(1000, speed.txBps)
        assertEquals(2000, speed.totalRxBytes)
        assertEquals(1000, speed.totalTxBytes)
    }

    @Test
    fun sample_counterResetDoesNotReportNegativeSpeed_andRebaselinesForNewTraffic() {
        val source = FakeTrafficByteSource(rx = 5000, tx = 5000)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        sampler.sample()
        source.rx = 100
        source.tx = 50
        now = 2000L
        val speed = sampler.sample()

        assertEquals(0, speed.rxBps)
        assertEquals(0, speed.txBps)
        assertEquals(0, speed.totalRxBytes)
        assertEquals(0, speed.totalTxBytes)

        source.rx = 600
        source.tx = 250
        now = 3000L
        val afterReset = sampler.sample()

        assertEquals(500, afterReset.rxBps)
        assertEquals(200, afterReset.txBps)
        assertEquals(500, afterReset.totalRxBytes)
        assertEquals(200, afterReset.totalTxBytes)
    }

    @Test
    fun resetBaseline_startsNewSessionAtZeroTotals() {
        val source = FakeTrafficByteSource(rx = 10_000, tx = 5_000)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        sampler.resetBaseline()
        val first = sampler.sample()
        assertEquals(0, first.rxBps)
        assertEquals(0, first.txBps)
        assertEquals(0, first.totalRxBytes)
        assertEquals(0, first.totalTxBytes)

        source.rx = 12_500
        source.tx = 6_500
        now = 2000L
        val second = sampler.sample()

        assertEquals(2500, second.rxBps)
        assertEquals(1500, second.txBps)
        assertEquals(2500, second.totalRxBytes)
        assertEquals(1500, second.totalTxBytes)
    }

    @Test
    fun formatNotification_usesReadableUnits() {
        val speed = TrafficSpeed(rxBps = 1536, txBps = 2L * 1024L * 1024L)

        assertEquals("Tunnel rate DL 1.5 KB/s  UL 2.0 MB/s", SpeedFormatter.formatNotification(speed))
    }

    @Test
    fun formatNotification_labelsNativeRateAsApproximate() {
        val speed = TrafficSpeed(rxBps = 1536, txBps = 2L * 1024L * 1024L)

        assertEquals(
            "Approx. tunnel rate DL 1.5 KB/s  UL 2.0 MB/s",
            SpeedFormatter.formatNotification(speed, approximate = true)
        )
    }

    @Test
    fun sample_snapshotSourceReadsRxAndTxAtomicallyOncePerSample() {
        val source = FakeSnapshotTrafficByteSource(rx = 1000, tx = 500)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        sampler.sample()
        source.rx = 2200
        source.tx = 900
        now = 2000L
        val speed = sampler.sample()

        assertEquals(2, source.snapshotCalls)
        assertEquals(1200, speed.rxBps)
        assertEquals(400, speed.txBps)
    }

    @Test
    fun sample_unavailableNativeSnapshotDoesNotResetCountersOrCreateAFakeSpike() {
        val source = FakeSnapshotTrafficByteSource(rx = 1000, tx = 500)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        sampler.sample()
        source.available = false
        now = 2000L
        val unavailable = sampler.sample()
        assertFalse(unavailable.sampleValid)
        assertEquals(0, unavailable.rxBps)
        assertEquals(0, unavailable.txBps)

        source.available = true
        source.rx = 3000
        source.tx = 1500
        now = 3000L
        val recovered = sampler.sample()

        assertTrue(recovered.sampleValid)
        assertEquals(1000, recovered.rxBps)
        assertEquals(500, recovered.txBps)
        assertEquals(2000, recovered.totalRxBytes)
        assertEquals(1000, recovered.totalTxBytes)
    }

    @Test
    fun sample_firstValidSnapshotAfterUnavailableSampleEstablishesBaseline() {
        val source = FakeSnapshotTrafficByteSource(rx = 0, tx = 0, available = false)
        var now = 1000L
        val sampler = TrafficSpeedSampler(source) { now }

        val unavailable = sampler.sample()
        assertFalse(unavailable.sampleValid)
        source.available = true
        source.rx = 5000
        source.tx = 2500
        now = 2000L
        val firstValid = sampler.sample()

        assertTrue(firstValid.sampleValid)
        assertEquals(0, firstValid.rxBps)
        assertEquals(0, firstValid.txBps)
        assertEquals(0, firstValid.totalRxBytes)
        assertEquals(0, firstValid.totalTxBytes)
    }

    @Test
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun updateTunnelTraffic_doesNotResurrectStateChangedBeforeCommit() {
        val backing = MutableStateFlow(connectedState())
        var switchPending = true
        fun switchToRecovery() {
            if (switchPending) {
                switchPending = false
                backing.value = backing.value.copy(
                    tunnelState = TunnelState.Starting,
                    tunnelVerified = false,
                    routeReady = false,
                    message = "Reconnecting"
                )
            }
        }
        val interrupted = object : MutableStateFlow<AppConnectionState> by backing {
            override var value: AppConnectionState
                get() = backing.value
                set(value) {
                    switchToRecovery()
                    backing.value = value
                }

            override fun compareAndSet(expect: AppConnectionState, update: AppConnectionState): Boolean {
                switchToRecovery()
                return backing.compareAndSet(expect, update)
            }
        }

        interrupted.updateTunnelTraffic(TrafficSpeed(rxBps = 5_000, txBps = 1_000))

        assertEquals(TunnelState.Starting, backing.value.tunnelState)
        assertFalse(backing.value.tunnelVerified)
        assertFalse(backing.value.routeReady)
        assertEquals("Reconnecting", backing.value.message)
        assertEquals(0L, backing.value.downloadBps)
    }

    @Test
    fun updateTunnelTraffic_preservesConnectionMetadataForValidMeasuredSample() {
        val connected = connectedState()
        val flow = MutableStateFlow(connected)

        flow.updateTunnelTraffic(TrafficSpeed(
            rxBps = 5_000, txBps = 1_000, totalRxBytes = 9_000, totalTxBytes = 2_000
        ))

        assertEquals(connected.copy(
            downloadBps = 5_000, uploadBps = 1_000, totalRxBytes = 9_000, totalTxBytes = 2_000
        ), flow.value)
    }

    @Test
    fun updateTunnelTraffic_dropsAnUnavailableNativeSample() {
        val connected = connectedState().copy(downloadBps = 2_000, totalRxBytes = 6_000)
        val flow = MutableStateFlow(connected)

        flow.updateTunnelTraffic(TrafficSpeed(sampleValid = false))

        assertEquals(connected, flow.value)
    }

    @Test
    fun updateTunnelTraffic_acceptsNativeUidApproximateSamples() {
        val connected = connectedState().copy(
            activeVpnEngine = ActiveVpnEngine.XrayNative,
            tunnelTrafficSource = TunnelTrafficSource.NativeUid
        )
        val flow = MutableStateFlow(connected)

        flow.updateTunnelTraffic(TrafficSpeed(
            rxBps = 7_000, txBps = 2_000, totalRxBytes = 12_000, totalTxBytes = 3_000
        ))

        assertEquals(connected.copy(
            downloadBps = 7_000, uploadBps = 2_000, totalRxBytes = 12_000, totalTxBytes = 3_000
        ), flow.value)
    }

    @Test
    fun updateTunnelTraffic_cannotShowHevNumbersForUnverifiedOrNativeTunnel() {
        val connected = connectedState()
        listOf(
            connected.copy(tunnelVerified = false),
            connected.copy(tunnelState = TunnelState.Error),
            connected.copy(activeVpnEngine = ActiveVpnEngine.XrayNative,
                tunnelTrafficSource = TunnelTrafficSource.Unavailable)
        ).forEach { state ->
            val flow = MutableStateFlow(state)
            flow.updateTunnelTraffic(TrafficSpeed(rxBps = 5_000))
            assertEquals(state, flow.value)
        }
    }

    private fun connectedState() = AppConnectionState(
        routeState = RouteState.Ready,
        tunnelState = TunnelState.Connected,
        message = "Connected",
        activeAccountId = "account",
        activeProfileId = "profile",
        routeReady = true,
        tunnelVerified = true,
        activeVpnEngine = ActiveVpnEngine.Hev,
        tunnelTrafficSource = TunnelTrafficSource.HevTun
    )

    private class FakeTrafficByteSource(
        var rx: Long,
        var tx: Long
    ) : TrafficByteSource {
        override fun rxBytes(): Long = rx
        override fun txBytes(): Long = tx
    }

    private class FakeSnapshotTrafficByteSource(
        var rx: Long,
        var tx: Long,
        var available: Boolean = true
    ) : TrafficByteSnapshotSource {
        var snapshotCalls = 0

        override fun snapshot(): TrafficBytes? {
            snapshotCalls++
            return if (available) TrafficBytes(rxBytes = rx, txBytes = tx) else null
        }
    }
}
