package com.g2ray.client.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.g2ray.client.G2RayApplication
import com.g2ray.client.MainActivity
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.Transport
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.parser.VlessUriParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Opt-in real preference/service/TUN interaction checks; never edits accounts or profiles. */
@RunWith(AndroidJUnit4::class)
class OptionMatrixDeviceTest {
    @Test fun loadedTrafficWithExplicitFailoverSettingRestoresPreferences() = runBlocking {
        assumeTrue("Requires explicit liveOptions=true", args().getString("liveOptions") == "true")
        assumeTrue("Requires explicit liveLoadedTransfers=true", args().getString("liveLoadedTransfers") == "true")
        val enabled = requireNotNull(args().getString("optionFailover")) { "Pass optionFailover=true or false" }.toBooleanStrict()
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        stop(context)
        val prefs = VpnEnginePreferences(context.secureStore)
        val original = prefs.readSettings()
        val selection = context.accountRepository.activeSelection.value
        val raw = OPTION_KEYS.associateWith { context.secureStore.getString(it, null) }
        val saved = backup(context)
        check(!saved.exists()) { "Restore the previous interrupted option run first" }
        saved.writeText(JSONObject(raw.mapValues { it.value ?: JSONObject.NULL }).toString())
        try {
            prefs.setSeamlessFailoverEnabled(enabled)
            report("loaded_option seamless_failover=$enabled")
            LiveVpnDeviceTest().selectedProfileKeepsResponsiveDuringConcurrentTransfers()
        } finally {
            withContext(NonCancellable) {
                try { stop(context) } finally {
                    context.secureStore.putStrings(raw)
                    DiagnosticEvents.setEnabled(original.diagnosticsEnabled)
                    assertEquals(original, prefs.readSettings())
                    assertEquals(selection, context.accountRepository.activeSelection.value)
                    check(saved.delete())
                }
            }
        }
    }

    @Test fun quickSettingsTileConnectsAndDisconnectsWithoutChangingSelection() = runBlocking {
        assumeTrue("Requires explicit liveOptions=true", args().getString("liveOptions") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        assertNull("Grant VPN permission first", VpnService.prepare(context))
        stop(context)
        val selection = context.accountRepository.activeSelection.value
        val preferences = VpnEnginePreferences(context.secureStore).readSettings()
        val component = "com.g2ray.client/com.g2ray.client.vpn.VpnTileService"
        val original = shell("settings --user 0 get secure sysui_qs_tiles").trim()
        val added = !original.contains(Regex("custom\\(com\\.g2ray\\.client/[^)]*VpnTileService\\)"))
        try {
            if (added) shell("cmd statusbar add-tile $component")
            shell("cmd statusbar expand-settings")
            delay(1_000)
            shell("cmd statusbar click-tile $component")
            val ready = withTimeout(35_000L) {
                VpnStateRepository.state.first { it.tunnelVerified || it.tunnelState in setOf(TunnelState.Error, TunnelState.Blocked) }
            }
            assertEquals("Tile did not establish a verified VPN", TunnelState.Connected, ready.tunnelState)
            assertTrue(ready.tunnelVerified)
            shell("cmd statusbar expand-settings")
            delay(500)
            shell("cmd statusbar click-tile $component")
            withTimeout(20_000L) {
                VpnStateRepository.state.first { it.tunnelState == TunnelState.Disconnected }
            }
            assertEquals(selection, context.accountRepository.activeSelection.value)
            assertEquals(preferences, VpnEnginePreferences(context.secureStore).readSettings())
            report("quick_settings_tile verified_start=true verified_stop=true selection_preserved=true")
        } finally {
            withContext(NonCancellable) {
                try { stop(context) } finally {
                    shell("cmd statusbar collapse")
                    if (added) shell("cmd statusbar remove-tile $component")
                    // SystemUI posts tile removal and the secure-setting write.
                    // Wait for that acknowledgement instead of racing its handler.
                    withTimeout(5_000L) {
                        while (shell("settings --user 0 get secure sysui_qs_tiles").trim() != original) delay(100)
                    }
                    assertEquals("Quick Settings arrangement was not restored", original,
                        shell("settings --user 0 get secure sysui_qs_tiles").trim())
                }
            }
        }
    }

    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
    }

    @Test fun diagnoseDpiComponentsWithoutChangingSavedProfiles() = runBlocking {
        assumeTrue("Requires explicit liveOptions=true", args().getString("liveOptions") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        stop(context)
        val selection = context.accountRepository.activeSelection.value
        val settings = VpnEnginePreferences(context.secureStore).readSettings()
        val selected = context.accountRepository.profiles.value.first { it.id == selection.profileId }
        val manager = XrayCoreManager()
        val output = File(context.filesDir, "g2ray-dpi-components.jsonl").apply { writeText("") }
        for (transport in Transport.entries) {
            val stored = context.accountRepository.profiles.value.first {
                it.accountId == selected.accountId && it.address == selected.address && it.transport == transport
            }
            val profile = VlessUriParser.parse(stored.rawUri).getOrThrow()
            val mask = JSONObject(XrayConfigBuilder.buildDelayTestConfig(profile, 10808,
                dpiCompatibilityEnabled = true)).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("finalmask").toString()
            val simple = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["100-200"],"delays":["0"],"maxSplit":"8"}}]}"""
            val variants = linkedMapOf("normal" to profile,
                "unsafe_only" to profile.copy(fingerprint = "unsafe", finalMaskJson = null),
                "mask_only" to profile.copy(finalMaskJson = mask),
                "unsafe_and_mask" to profile.copy(fingerprint = "unsafe", finalMaskJson = mask),
                "simple_mask" to profile.copy(finalMaskJson = simple))
            for ((name, candidate) in variants) {
                val result = withContext(Dispatchers.IO) {
                    manager.measureDelay(candidate, context.filesDir.absolutePath, 5,
                        "https://cp.cloudflare.com/generate_204")
                }
                output.appendText(JSONObject().put("transport", transport.name).put("variant", name)
                    .put("success", result.success).put("delay_ms", result.delayMs)
                    .put("error", result.error).toString() + "\n")
                report("dpi_component transport=$transport variant=$name success=${result.success} delay_ms=${result.delayMs}")
                if (name == "normal") assertTrue("Normal $transport fixture failed", result.success)
            }
        }
        assertEquals(selection, context.accountRepository.activeSelection.value)
        assertEquals(settings, VpnEnginePreferences(context.secureStore).readSettings())
    }

    @Test fun allDataPathSwitchEngineAndTransportCombinations() = runBlocking {
        assumeTrue("Requires explicit liveOptions=true", args().getString("liveOptions") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        assertNull("Grant VPN permission first", VpnService.prepare(context))
        val prefs = VpnEnginePreferences(context.secureStore)
        val original = prefs.readSettings()
        val selection = context.accountRepository.activeSelection.value
        val selected = context.accountRepository.profiles.value.first { it.id == selection.profileId }
        val profiles = Transport.entries.associateWith { transport ->
            context.accountRepository.profiles.value.firstOrNull {
                it.accountId == selected.accountId && it.address == selected.address && it.transport == transport
            } ?: error("A matching saved $transport profile is required")
        }
        val raw = OPTION_KEYS.associateWith { context.secureStore.getString(it, null) }
        val backup = backup(context)
        check(!backup.exists()) { "Restore the previous interrupted option run first" }
        backup.writeText(JSONObject(raw.mapValues { it.value ?: JSONObject.NULL }).toString())
        val receipt = File(context.filesDir, "g2ray-options-matrix.jsonl").apply { writeText("") }
        val failures = mutableListOf<String>()
        var completed = 0
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            stop(context)
            // Keep exploration side effects out of a data-path comparison.
            // Auto-optimization has independent UI/runtime and source tests.
            prefs.setAutoRouteOptimizationEnabled(false)
            prefs.setRoutingPolicy(RoutingPolicy())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val onlyMode = args().getString("optionEngine")
            val modes = VpnEngineMode.entries.filter { onlyMode == null || it.storageValue == onlyMode }
            require(modes.isNotEmpty()) { "Unknown optionEngine" }
            val onlyMask = args().getString("optionMask")?.toInt()?.also { require(it in 0..15) }
            val onlyDpi = args().getString("optionDpi")?.toBooleanStrict()
            val masks = (0..15).filter { (onlyMask == null || it == onlyMask) && (onlyDpi == null || bit(it, 2) == onlyDpi) }
            require(masks.isNotEmpty()) { "No matching option combinations" }
            for (mode in modes) for ((transport, profile) in profiles) for (mask in 0..15) {
                if (mask !in masks) continue
                val id = "${mode.storageValue}-${transport.name}-$mask"
                val row = JSONObject().put("id", id).put("mode", mode.storageValue).put("transport", transport.name)
                    .put("fast_dns", bit(mask, 0)).put("ipv4_only", bit(mask, 1))
                    .put("dpi", bit(mask, 2)).put("seamless", bit(mask, 3))
                var client: OkHttpClient? = null
                try {
                    stop(context)
                    prefs.setMode(mode)
                    prefs.setFastDnsEnabled(bit(mask, 0))
                    prefs.setIpv4OnlyEnabled(bit(mask, 1))
                    prefs.setDpiCompatibilityEnabled(bit(mask, 2))
                    prefs.setSeamlessFailoverEnabled(bit(mask, 3))
                    val diagnostics = bit(mask, 0) xor bit(mask, 2)
                    prefs.setDiagnosticsEnabled(diagnostics)
                    DiagnosticEvents.setEnabled(diagnostics)
                    val start = SystemClock.elapsedRealtime()
                    scenario.onActivity { activity ->
                        ContextCompat.startForegroundService(activity, Intent(activity, G2RayVpnService::class.java).apply {
                            action = G2RayVpnService.ACTION_START
                            putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, profile.rawUri)
                            putExtra(G2RayVpnService.EXTRA_ACCOUNT_ID, profile.accountId)
                            putExtra(G2RayVpnService.EXTRA_VPN_ENGINE_MODE, mode.storageValue)
                        })
                    }
                    val state = withTimeoutOrNull(35_000L) {
                        VpnStateRepository.state.first {
                            it.tunnelVerified || it.tunnelState in setOf(TunnelState.Error, TunnelState.Blocked)
                        }
                    } ?: throw AssertionError("$id startup did not verify within 35s: " +
                        VpnStateRepository.state.value.let { "${it.tunnelState}/${it.routeState}" })
                    assertEquals("$id: ${state.message}", TunnelState.Connected, state.tunnelState)
                    assertTrue("$id did not verify", state.tunnelVerified)
                    val actual = requireNotNull(state.activeVpnEngine)
                    if (mode != VpnEngineMode.Auto) assertEquals(id,
                        if (mode == VpnEngineMode.Hev) ActiveVpnEngine.Hev else ActiveVpnEngine.XrayNative, actual)
                    row.put("actual_engine", actual.name).put("verified_start_ms", SystemClock.elapsedRealtime() - start)
                    val service = requireNotNull(SocketProtector.vpnService)
                    for ((field, expected) in mapOf("fastDnsEnabled" to bit(mask, 0), "ipv4Only" to bit(mask, 1),
                        "dpiCompatibilityEnabled" to bit(mask, 2), "seamlessFailoverEnabled" to bit(mask, 3))) {
                        assertEquals("$id: stored option was not consumed: $field", expected,
                            G2RayVpnService::class.java.getDeclaredField(field).apply { isAccessible = true }.getBoolean(service))
                    }
                    val network = context.getSystemService(ConnectivityManager::class.java)
                    assertTrue("$id default route is not VPN", network.getNetworkCapabilities(network.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                    val probeClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                        .followRedirects(false).followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
                    client = probeClient
                    for (round in listOf("fresh", "warm")) {
                        val outcomes = coroutineScope {
                            SocksRunningRouteProbe.HEALTH_URLS.map { url -> async(Dispatchers.IO) {
                                val at = SystemClock.elapsedRealtime()
                                val result = runCatching {
                                    probeClient.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
                                        .execute().let { evaluateRouteProbeResponse(url, it) }
                                }.getOrElse { SocksRunningRouteProbe.RouteProbeOutcome(error = it.javaClass.simpleName) }
                                result.success to (SystemClock.elapsedRealtime() - at)
                            } }.awaitAll()
                        }
                        row.put("${round}_successes", outcomes.count { it.first })
                        row.put("${round}_durations_ms", org.json.JSONArray(outcomes.map { it.second }))
                        assertTrue("$id $round full-VPN quorum failed", outcomes.count { it.first } >= 2)
                    }
                    assertEquals("$id changed selection", selection, context.accountRepository.activeSelection.value)
                    row.put("passed", true)
                } catch (error: Throwable) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (error !is Exception && error !is AssertionError) throw error
                    row.put("passed", false).put("failure", error.javaClass.simpleName)
                    row.put("state", VpnStateRepository.state.value.tunnelState.name)
                    row.put("events", DiagnosticEvents.export())
                    failures += "$id: ${error.message?.take(240)}"
                } finally {
                    client?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
                    row.put("pss_kib", Debug.getPss())
                    receipt.appendText(row.toString() + "\n")
                    completed++
                    report("option_matrix completed=$completed passed=${completed - failures.size} last=$id")
                    withContext(NonCancellable) { stop(context) }
                }
            }
            assertEquals("Matrix skipped an option combination", modes.size * profiles.size * masks.size, completed)
            assertTrue("Option failures (${failures.size}): ${failures.joinToString("; ")}", failures.isEmpty())
        } finally {
            withContext(NonCancellable) {
                try { stop(context) } finally {
                    context.secureStore.putStrings(raw)
                    DiagnosticEvents.setEnabled(original.diagnosticsEnabled)
                    assertEquals("Original settings were not restored", original, prefs.readSettings())
                    assertEquals("Original selection was not preserved", selection, context.accountRepository.activeSelection.value)
                    check(backup.delete()) { "Could not remove the restored option backup" }
                    scenario?.close()
                }
            }
        }
    }

    @Test fun restoreSettingsAfterInterruptedOptionRun() = runBlocking {
        assumeTrue("Requires explicit liveOptions=true", args().getString("liveOptions") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        val file = backup(context)
        if (!file.exists()) { report("option_restore needed=false"); return@runBlocking }
        val saved = JSONObject(file.readText())
        check(saved.keys().asSequence().toSet() == OPTION_KEYS.toSet()) { "Invalid option backup" }
        stop(context)
        context.secureStore.putStrings(OPTION_KEYS.associateWith { if (saved.isNull(it)) null else saved.getString(it) })
        DiagnosticEvents.setEnabled(VpnEnginePreferences(context.secureStore).isDiagnosticsEnabled())
        check(file.delete())
        report("option_restore restored=true")
    }

    private suspend fun stop(context: Context) {
        check(VpnStateRepository.state.value.alwaysOnVpn != true) { "Do not run destructive option tests under Android always-on" }
        if (SocketProtector.vpnService == null && VpnStateRepository.state.value.tunnelState in
            setOf(TunnelState.Disabled, TunnelState.Disconnected)) return
        context.startService(Intent(context, G2RayVpnService::class.java).setAction(G2RayVpnService.ACTION_STOP))
        withTimeout(20_000L) {
            VpnStateRepository.state.first { it.tunnelState in setOf(TunnelState.Disconnected, TunnelState.Disabled) }
            while (SocketProtector.vpnService != null) delay(50L)
        }
    }

    private fun bit(mask: Int, index: Int) = mask and (1 shl index) != 0
    private fun args() = InstrumentationRegistry.getArguments()
    private fun backup(context: Context) = File(context.filesDir, "g2ray-option-settings-backup.json")
    private fun report(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "\n$message\n") })
    companion object {
        internal val OPTION_KEYS = listOf("vpn_engine_mode", "vpn_fast_dns_enabled", "vpn_ipv4_only_enabled",
            "vpn_dpi_compatibility_enabled", "vpn_auto_route_optimization_enabled", "vpn_seamless_failover_enabled",
            "vpn_bypass_ir", "vpn_bypass_domains", "vpn_excluded_packages", "vpn_diagnostics_enabled")
    }
}
