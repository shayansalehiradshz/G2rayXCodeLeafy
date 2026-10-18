package com.g2ray.client.vpn

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.g2ray.client.MainActivity
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class VpnTileService : TileService() {
    private var listening: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            VpnStateRepository.state.map { it.tunnelState to it.tunnelVerified }.distinctUntilChanged().collect { (state, verified) ->
                qsTile?.apply {
                    this.state = when (state) {
                        TunnelState.Stopping -> Tile.STATE_UNAVAILABLE
                        TunnelState.Starting, TunnelState.Connected, TunnelState.Blocked -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    label = "G2Ray VPN"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = when (state) {
                        TunnelState.Connected -> if (verified) "Connected" else "Recovering"
                        TunnelState.Starting -> "Connecting"
                        TunnelState.Blocked -> "Blocked"
                        else -> "Disconnected"
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val state = VpnStateRepository.state.value
            if (state.tunnelState == TunnelState.Stopping) return@unlockAndRun
            if (state.alwaysOnVpn != true && state.tunnelState in setOf(TunnelState.Connected, TunnelState.Starting, TunnelState.Blocked)) {
                requestStopOrOpenApp(
                    { startService(Intent(this, G2RayVpnService::class.java).setAction(G2RayVpnService.ACTION_STOP)) },
                    { openApp(connect = false) }
                )
            } else {
                openApp(connect = state.alwaysOnVpn != true)
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun openApp(connect: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = if (connect) ACTION_CONNECT_FROM_TILE else Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else launchBeforeAndroid14(intent)
    }

    // The PendingIntent overload does not exist before API 34.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBeforeAndroid14(intent: Intent) = startActivityAndCollapse(intent)

    companion object {
        const val ACTION_CONNECT_FROM_TILE = "com.g2ray.client.CONNECT_FROM_TILE"

        internal fun requestStopOrOpenApp(requestStop: () -> Unit, openApp: () -> Unit) {
            try {
                requestStop()
            } catch (_: IllegalStateException) {
                openApp()
            } catch (_: SecurityException) {
                openApp()
            }
        }
    }
}
