package com.g2ray.client.model

import kotlinx.coroutines.flow.MutableStateFlow

object VpnStateRepository {
    val state = MutableStateFlow(
        AppConnectionState(
            routeState = RouteState.Unknown,
            tunnelState = TunnelState.Disabled,
            message = "Disconnected",
            activeAccountId = null,
            activeProfileId = null,
            routeReady = false
        )
    )
}
