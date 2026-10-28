package com.g2ray.client.vpn

import android.net.TrafficStats
import android.os.Process

/**
 * Coarse physical-byte progress for the Native Xray engine.
 *
 * Android does not expose Xray's native TUN counters through the bundled
 * libXray API. Per-UID counters are therefore used only for recovery hints;
 * they are never displayed as tunnel speed and are not treated as proof of a
 * successful route without the normal end-to-end quorum.
 */
class AndroidUidTrafficByteSource(
    private val uid: Int = Process.myUid(),
    private val readRxBytes: (Int) -> Long = { TrafficStats.getUidRxBytes(it) },
    private val readTxBytes: (Int) -> Long = { TrafficStats.getUidTxBytes(it) }
) : TrafficByteSnapshotSource {
    override fun snapshot(): TrafficBytes? {
        val rx = readRxBytes(uid)
        val tx = readTxBytes(uid)
        if (rx < 0L || tx < 0L) return null
        return TrafficBytes(rxBytes = rx, txBytes = tx)
    }
}
