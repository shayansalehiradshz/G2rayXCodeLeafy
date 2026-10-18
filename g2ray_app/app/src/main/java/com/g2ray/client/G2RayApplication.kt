package com.g2ray.client

import android.app.Application
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.EncryptedPrefsStore
import com.g2ray.client.network.RouteMeasurementContext
import com.g2ray.client.vpn.DiagnosticEvents
import com.g2ray.client.vpn.VpnEnginePreferences
import kotlinx.coroutines.*

class G2RayApplication : Application() {
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        diagnosticScope.launch {
            DiagnosticEvents.setEnabled(runCatching { VpnEnginePreferences(secureStore).isDiagnosticsEnabled() }.getOrDefault(false))
        }
    }
    internal val secureStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EncryptedPrefsStore(this) }
    val routeMeasurementContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { RouteMeasurementContext(this) }

    val accountRepository: AccountRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AccountRepository(secureStore)
    }
}
