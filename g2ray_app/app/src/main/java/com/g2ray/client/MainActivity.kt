package com.g2ray.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import com.g2ray.client.vpn.VpnTileService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.g2ray.client.ui.G2RayViewModel
import com.g2ray.client.ui.HomeScreen
import com.g2ray.client.ui.SettingsScreen
import com.g2ray.client.ui.theme.G2RayTheme
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.vpn.G2RayVpnService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: G2RayViewModel by viewModels()
    private val tileConnectRequest = mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == VpnTileService.ACTION_CONNECT_FROM_TILE) tileConnectRequest.intValue++
    }

    override fun onStart() {
        super.onStart()
        // Re-check a tunnel after the user returns from another app. The
        // foreground service remains the owner of recovery; this only asks it
        // to verify the existing live route and repair it if necessary.
        if (VpnStateRepository.state.value.tunnelState == TunnelState.Connected) {
            startService(Intent(this, G2RayVpnService::class.java).apply {
                action = G2RayVpnService.ACTION_REVERIFY
            })
        } else if (VpnStateRepository.state.value.tunnelState in setOf(TunnelState.Starting, TunnelState.Blocked)) {
            startService(Intent(this, G2RayVpnService::class.java).apply {
                action = G2RayVpnService.ACTION_REFRESH_POLICY
            })
        } else {
            viewModel.autoOptimizeRoutesIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == VpnTileService.ACTION_CONNECT_FROM_TILE) tileConnectRequest.intValue++
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.measurementNetworkKey.filterNotNull().collect {
                    viewModel.autoOptimizeRoutesIfNeeded()
                }
            }
        }

        setContent {
            G2RayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    LaunchedEffect(tileConnectRequest.intValue) {
                        if (tileConnectRequest.intValue > 0) navController.navigate("home") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                connectRequest = tileConnectRequest.intValue,
                                onConnectRequestHandled = {
                                    tileConnectRequest.intValue = 0
                                    intent.action = Intent.ACTION_MAIN
                                },
                                onNavigateToSettings = { navController.navigate("settings") { launchSingleTop = true } }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
