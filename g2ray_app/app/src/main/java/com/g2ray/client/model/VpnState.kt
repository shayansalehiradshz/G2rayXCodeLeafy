package com.g2ray.client.model

enum class RouteState {
    Unknown,
    Waking,
    Settling,
    Ready,
    Error
}

enum class TunnelState {
    Disabled,
    Disconnected,
    PreparingPermission,
    Starting,
    Connected,
    Stopping,
    Error,
    Blocked
}

data class AppConnectionState(
    val routeState: RouteState,
    val tunnelState: TunnelState,
    val message: String,
    val activeAccountId: String? = null,
    val activeProfileId: String? = null,
    val routeReady: Boolean = false,
    val tunnelVerified: Boolean = false,
    val downloadBps: Long = 0,
    val uploadBps: Long = 0,
    val totalRxBytes: Long = 0,
    val totalTxBytes: Long = 0,
    val activeVpnEngine: ActiveVpnEngine? = null,
    val tunnelTrafficSource: TunnelTrafficSource = TunnelTrafficSource.Unavailable,
    val alwaysOnVpn: Boolean? = null,
    val blockConnectionsWithoutVpn: Boolean? = null
)
