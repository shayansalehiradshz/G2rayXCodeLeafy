package com.g2ray.client.vpn

import com.g2ray.client.model.AppConnectionState
import java.util.ArrayDeque
import com.g2ray.client.model.VpnStateRepository
import kotlinx.coroutines.*

/** Event-only, in-memory diagnostics: no packet callbacks, browsing URLs, credentials or disk writes. */
internal object DiagnosticEvents {
    private val events = ArrayDeque<String>()
    private var enabled = false
    private var previous: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observation: Job? = null
    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            observation?.cancel(); observation = null
            events.clear(); previous = null
        } else if (observation?.isActive != true) {
            observation = scope.launch { VpnStateRepository.state.collect { record(it) } }
        }
    }
    @Synchronized fun record(state: AppConnectionState, now: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val summary = "tunnel=${state.tunnelState} route=${state.routeState} verified=${state.tunnelVerified} engine=${state.activeVpnEngine} category=${category(state.message)}"
        if (summary == previous) return
        previous = summary
        while (events.size >= 200) events.removeFirst()
        events.addLast("$now $summary")
    }
    @Synchronized fun export(): String = "G2Ray connection events (timestamps: epoch milliseconds)\n" +
        if (!enabled) "Diagnostics disabled." else events.joinToString("\n").ifEmpty { "No connection transitions recorded." }

    private fun category(message: String): String {
        val text = message.lowercase(java.util.Locale.ROOT)
        return when {
            "quota" in text || "billing" in text -> "quota"
            "rate limit" in text || "rate-limit" in text -> "rate_limit"
            "permission" in text -> "permission"
            "dns" in text || "resolv" in text -> "dns"
            "timeout" in text || "timed out" in text -> "timeout"
            "config" in text || "invalid" in text -> "configuration"
            "network" in text || "bind" in text -> "network"
            "wake" in text || "codespace" in text -> "host"
            else -> "state"
        }
    }
}
