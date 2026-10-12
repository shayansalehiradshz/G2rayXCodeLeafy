package com.g2ray.client.vpn

import java.io.File

data class HevTunnelStats(
    val uploadPackets: Long,
    val uploadBytes: Long,
    val downloadPackets: Long,
    val downloadBytes: Long
)

/**
 * Thin binding to the MIT-licensed hev-socks5-tunnel Android library. The
 * native library has its JNI class name compiled specifically for this class.
 */
class HevTunController private constructor() {
    companion object {
        private const val LIBRARY_NAME = "hev-socks5-tunnel"
        private const val CONFIG_FILE_NAME = "g2ray-hev-tun.yaml"
        // Use HEV's upstream five-minute idle timeout. Mobile NATs and remote
        // edges can discard an idle flow earlier than Xray's connIdle policy;
        // retaining that stale local mapping longer makes an app wait on a
        // socket that only appears alive instead of reconnecting promptly.
        private const val TCP_READ_WRITE_TIMEOUT_MS = 300_000
        private const val UDP_READ_WRITE_TIMEOUT_MS = 60_000

        @Volatile
        private var loadAttempted = false

        @Volatile
        private var nativeAvailable = false

        @Volatile
        private var nativeLoadError: String? = null

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        fun isAvailable(): Boolean {
            ensureNativeLoaded()
            return nativeAvailable
        }

        fun loadError(): String? {
            ensureNativeLoaded()
            return nativeLoadError
        }

        fun start(
            filesDir: File,
            tunFd: Int,
            socksPort: Int,
            mtu: Int,
            ipv4Only: Boolean = false
        ) {
            check(isAvailable()) { nativeLoadError ?: "HEV TUN library is not bundled" }
            require(tunFd >= 0) { "VPN file descriptor is invalid" }
            require(socksPort in 1..65535) { "HEV SOCKS port is invalid" }
            require(mtu in 1280..1500) { "HEV TUN MTU must be between 1280 and 1500" }

            val configFile = File(filesDir, CONFIG_FILE_NAME)
            configFile.writeText(buildConfig(socksPort, mtu, ipv4Only))
            check(TProxyStartService(configFile.absolutePath, tunFd)) {
                "HEV TUN native runtime rejected the start request"
            }
            if (!isRunning()) {
                runCatching { TProxyStopService() }
                throw IllegalStateException("HEV TUN native runtime stopped during startup")
            }
        }

        fun stop(): Boolean {
            return !isAvailable() || runCatching { TProxyStopService() }.getOrDefault(false)
        }

        fun isRunning(): Boolean {
            return isAvailable() && runCatching { TProxyIsRunning() }.getOrDefault(false)
        }

        fun statsOrNull(): HevTunnelStats? {
            if (!isRunning()) return null
            return runCatching {
                val stats = TProxyGetStats() ?: return null
                if (stats.size < 4) return null
                // HEV returns tx packets/bytes then rx packets/bytes. The VPN TUN
                // transmits phone-originated traffic and receives downlink traffic.
                HevTunnelStats(
                    uploadPackets = stats[0].coerceAtLeast(0),
                    uploadBytes = stats[1].coerceAtLeast(0),
                    downloadPackets = stats[2].coerceAtLeast(0),
                    downloadBytes = stats[3].coerceAtLeast(0)
                )
            }.getOrNull()
        }

        internal fun buildConfig(socksPort: Int, mtu: Int, ipv4Only: Boolean = false): String {
            require(socksPort in 1..65535) { "HEV SOCKS port is invalid" }
            require(mtu in 1280..1500) { "HEV TUN MTU must be between 1280 and 1500" }
            return buildString {
                appendLine("tunnel:")
                appendLine("  mtu: $mtu")
                appendLine("  ipv4: 198.18.0.1")
                if (!ipv4Only) {
                    appendLine("  ipv6: 'fc00::1'")
                }
                appendLine("socks5:")
                appendLine("  port: $socksPort")
                appendLine("  address: 127.0.0.1")
                appendLine("  udp: 'udp'")
                appendLine("misc:")
                appendLine("  tcp-read-write-timeout: $TCP_READ_WRITE_TIMEOUT_MS")
                appendLine("  udp-read-write-timeout: $UDP_READ_WRITE_TIMEOUT_MS")
                appendLine("  log-level: warn")
            }
        }

        private fun ensureNativeLoaded() {
            if (loadAttempted) return
            synchronized(this) {
                if (loadAttempted) return
                loadAttempted = true
                try {
                    System.loadLibrary(LIBRARY_NAME)
                    nativeAvailable = true
                    nativeLoadError = null
                } catch (error: Throwable) {
                    nativeAvailable = false
                    nativeLoadError = error.localizedMessage ?: error.javaClass.simpleName
                }
            }
        }
    }
}

class HevTunnelByteSource : TrafficByteSnapshotSource {
    override fun snapshot(): TrafficBytes? {
        val stats = HevTunController.statsOrNull() ?: return null
        return TrafficBytes(
            rxBytes = stats.downloadBytes,
            txBytes = stats.uploadBytes,
            rxPackets = stats.downloadPackets,
            txPackets = stats.uploadPackets
        )
    }
}
