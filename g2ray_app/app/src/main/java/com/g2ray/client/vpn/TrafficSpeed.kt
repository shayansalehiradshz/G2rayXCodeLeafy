package com.g2ray.client.vpn

import android.os.SystemClock
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.TunnelTrafficSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

data class TrafficSpeed(
    val rxBps: Long = 0,
    val txBps: Long = 0,
    val totalRxBytes: Long = 0,
    val totalTxBytes: Long = 0,
    val rxPackets: Long = 0,
    val txPackets: Long = 0,
    val sampleValid: Boolean = true
) {
    val hasTraffic: Boolean
        get() = sampleValid && (rxBps > 0 || txBps > 0 || totalRxBytes > 0 || totalTxBytes > 0)
}

interface TrafficByteSource {
    fun rxBytes(): Long
    fun txBytes(): Long
}

/**
 * A source that can obtain RX and TX together. This avoids sampling two
 * independent native counters at slightly different instants.
 */
data class TrafficBytes(
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long = 0,
    val txPackets: Long = 0,
    val rxTcpPayloadBytes: Long? = null
)

interface TrafficByteSnapshotSource : TrafficByteSource {
    fun snapshot(): TrafficBytes?

    override fun rxBytes(): Long = snapshot()?.rxBytes ?: 0

    override fun txBytes(): Long = snapshot()?.txBytes ?: 0
}

class TrafficSpeedSampler(
    private val source: TrafficByteSource,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var baseRx: Long? = null
    private var baseTx: Long? = null
    private var lastRx: Long? = null
    private var lastTx: Long? = null
    private var lastRxPackets: Long = 0
    private var lastTxPackets: Long = 0
    private var lastAtMs: Long? = null

    fun resetBaseline() {
        baseRx = null
        baseTx = null
        lastRx = null
        lastTx = null
        lastRxPackets = 0
        lastTxPackets = 0
        lastAtMs = null
    }

    fun sample(): TrafficSpeed {
        val snapshotSource = source as? TrafficByteSnapshotSource
        val snapshot = snapshotSource?.snapshot()
        if (snapshotSource != null && snapshot == null) {
            return lastKnownTotals()
        }
        val counters = snapshot ?: TrafficBytes(source.rxBytes(), source.txBytes())
        val rx = max(0, counters.rxBytes)
        val tx = max(0, counters.txBytes)
        val at = nowMs()

        val previousRx = lastRx
        val previousTx = lastTx
        val previousAt = lastAtMs

        var rxBase = baseRx ?: rx.also { baseRx = it }
        var txBase = baseTx ?: tx.also { baseTx = it }
        val rxReset = rx < rxBase || (previousRx != null && rx < previousRx)
        val txReset = tx < txBase || (previousTx != null && tx < previousTx)
        if (rxReset) {
            baseRx = rx
            rxBase = rx
        }
        if (txReset) {
            baseTx = tx
            txBase = tx
        }
        val sessionRx = max(0, rx - rxBase)
        val sessionTx = max(0, tx - txBase)

        lastRx = rx
        lastTx = tx
        lastRxPackets = max(0, counters.rxPackets)
        lastTxPackets = max(0, counters.txPackets)
        lastAtMs = at

        if (previousRx == null || previousTx == null || previousAt == null || at <= previousAt || rxReset || txReset) {
            return TrafficSpeed(
                totalRxBytes = sessionRx,
                totalTxBytes = sessionTx,
                rxPackets = lastRxPackets,
                txPackets = lastTxPackets
            )
        }

        val elapsedMs = at - previousAt
        val rxDelta = max(0, rx - previousRx)
        val txDelta = max(0, tx - previousTx)

        return TrafficSpeed(
            rxBps = rxDelta * 1000L / elapsedMs,
            txBps = txDelta * 1000L / elapsedMs,
            totalRxBytes = sessionRx,
            totalTxBytes = sessionTx,
            rxPackets = lastRxPackets,
            txPackets = lastTxPackets
        )
    }

    private fun lastKnownTotals(): TrafficSpeed {
        val rx = lastRx ?: return TrafficSpeed(sampleValid = false)
        val tx = lastTx ?: return TrafficSpeed(sampleValid = false)
        val rxBase = baseRx ?: rx
        val txBase = baseTx ?: tx
        return TrafficSpeed(
            totalRxBytes = max(0, rx - rxBase),
            totalTxBytes = max(0, tx - txBase),
            rxPackets = lastRxPackets,
            txPackets = lastTxPackets,
            sampleValid = false
        )
    }
}

object SpeedFormatter {
    fun formatBps(bytesPerSecond: Long): String {
        val bps = max(0, bytesPerSecond)
        return when {
            bps >= 1024L * 1024L -> "%.1f MB/s".format(bps / 1024.0 / 1024.0)
            bps >= 1024L -> "%.1f KB/s".format(bps / 1024.0)
            else -> "$bps B/s"
        }
    }

    fun formatNotification(speed: TrafficSpeed): String {
        return "Tunnel rate DL ${formatBps(speed.rxBps)}  UL ${formatBps(speed.txBps)}"
    }
}

internal fun MutableStateFlow<AppConnectionState>.updateTunnelTraffic(speed: TrafficSpeed) {
    if (!speed.sampleValid) return
    update { previous ->
        if (
            !previous.tunnelVerified || previous.tunnelState != TunnelState.Connected ||
            previous.tunnelTrafficSource != TunnelTrafficSource.HevTun
        ) {
            previous
        } else {
            previous.copy(
                downloadBps = speed.rxBps,
                uploadBps = speed.txBps,
                totalRxBytes = speed.totalRxBytes,
                totalTxBytes = speed.totalTxBytes
            )
        }
    }
}
