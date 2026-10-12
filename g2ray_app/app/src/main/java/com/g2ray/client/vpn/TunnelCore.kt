package com.g2ray.client.vpn

import com.g2ray.client.model.G2RayProfile
import kotlinx.coroutines.flow.StateFlow

interface TunnelCore {
    suspend fun start(profile: G2RayProfile, tunFd: Int, datDir: String, protectSocket: (Int) -> Boolean)
    suspend fun stop()
    val state: StateFlow<TunnelCoreState>
}
