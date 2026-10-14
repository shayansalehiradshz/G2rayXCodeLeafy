package com.g2ray.client.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.g2ray.client.G2RayApplication
import com.g2ray.client.MainActivity
import com.g2ray.client.ui.G2RayViewModel
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.ApiKeyKind
import com.g2ray.client.model.CodespaceWakeMode
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.TunnelTrafficSource
import com.g2ray.client.model.Transport
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.network.CodespaceWakeCoordinator
import com.g2ray.client.network.CodespaceWakeOutcome
import com.g2ray.client.network.CodespaceWakeRequest
import com.g2ray.client.network.GitHubCodespaceCoordinator
import com.g2ray.client.network.GitHubNetwork
import com.g2ray.client.network.WorkerNetwork
import com.g2ray.client.network.WorkerRouteCoordinator
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveVpnDeviceTest {
    @Test
    fun debugDataPathClientAttachesPerRequestTimingWithoutNetworkAccess() = runBlocking {
        assertTrue("This regression requires the actual debug client", com.g2ray.client.BuildConfig.DEBUG)
        val probe = OkHttpVpnDataPathProbe()
        try {
            val method = OkHttpVpnDataPathProbe::class.java.getDeclaredMethod("client").apply { isAccessible = true }
            val client = method.invoke(probe) as OkHttpClient
            val trace = VpnProbeTimingTrace(SystemClock::elapsedRealtime)
            client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204")
                .tag(VpnProbeTimingTrace::class.java, trace).build())
            val records = trace.summaries()
            assertEquals("Actual packaged client did not attach the timing listener", 1, records.size)
            assertTrue(records.single().contains("endpoint=www.gstatic.com outcome=pending"))
            report("event=probe_timing_factory attached=true network_access=false")
        } finally {
            probe.reset()
        }
    }

    @Test
    fun explicitEngineArgumentsCannotSilentlyExerciseAnotherEngine() {
        assertEquals(VpnEngineMode.Hev, engineFromTestArgument(null))
        VpnEngineMode.entries.forEach { mode ->
            assertEquals(mode, engineFromTestArgument(mode.storageValue))
        }
        listOf("native", "", "unknown", "HEV").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { engineFromTestArgument(value) }
        }
    }

    @Test
    fun selectedCodespacePingAllCompletesWithVisibleResults() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real delay scans require -e livePing true", args.getString("livePing") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertTrue("Stop VPN before refreshing real delays",
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true)
        assertTrue("Saved data failed to load", context.accountRepository.loadError.value == null)
        val selection = context.accountRepository.activeSelection.value
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: G2RayViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(
                owner, ViewModelProvider.AndroidViewModelFactory(context)
            )[G2RayViewModel::class.java]
        }
        try {
            val candidates = withTimeout(15_000L) {
                viewModel.activeCodespaceProfiles.first { it.isNotEmpty() }
            }
            val ids = candidates.map { it.id }
            val startedAt = SystemClock.elapsedRealtime()
            // Exercise the Home action without an activity's automatic optimizer
            // starting a second scan and changing selection during this check.
            instrumentation.runOnMainSync {
                viewModel.refreshActiveCodespaceDelays()
                assertTrue("Ping all did not enter testing state for every config",
                    ids.all { viewModel.profileDelayStates.value[it]?.testing == true })
            }
            val budgetMs = (ids.size * 12_000L + 15_000L).coerceIn(60_000L, 300_000L)
            val results = withTimeout(budgetMs) {
                viewModel.profileDelayStates.first { states ->
                    ids.all { id ->
                        val state = states[id]
                        state != null && !state.testing && state.testedAt != null &&
                            (state.delayMs != null || !state.error.isNullOrBlank())
                    }
                }
            }
            val storedById = context.accountRepository.profiles.value.associateBy { it.id }
            ids.forEach { id ->
                val state = results.getValue(id)
                if (state.delayMs != null) {
                    assertTrue("A successful ping has no real sample",
                        state.delayMs in 1L..9_999L && state.successfulSamples > 0 && state.sampleCount > 0)
                }
                assertEquals("Visible ping result was not persisted", state.delayMs, storedById[id]?.lastDelayMs)
            }
            val successful = ids.count { results.getValue(it).delayMs != null }
            assertTrue("No saved config passed a real delay check", successful > 0)
            assertEquals("Ping all changed the selected account/profile/key", selection,
                context.accountRepository.activeSelection.value)
            assertTrue("The delay scan left the native core running", !ReflectionXrayBridge().getState())
            report("event=ping_all_finished total=${ids.size} successful=$successful " +
                "failed=${ids.size - successful} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
        }
    }

    @Test
    fun selectedAccountWakesOnPhysicalNetwork() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Wake tests require -e liveWake true", args.getString("liveWake") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertTrue("Stop the VPN before the standalone wake test",
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true)
        val request = selectedWakeRequest(context)
        report("event=wake_configuration mode=${request.mode} " +
            "worker_configured=${request.workerConfigured} direct_configured=${request.githubDirectConfigured} " +
            "worker_aliases=${request.workerBaseUrls.size}")
        val coordinator = CodespaceWakeCoordinator(
            WorkerRouteCoordinator(WorkerNetwork.createApiService(loggingEnabled = false)),
            GitHubCodespaceCoordinator(GitHubNetwork.createApiService(loggingEnabled = false))
        )
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = coordinator.ensureReady(request, maxWaitMs = 90_000L) {
            report("event=wake_progress backend=${it.backend} phase=${it.phase} attempt=${it.attempt} " +
                "retry_after_ms=${it.retryAfterMs} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
        when (outcome) {
            is CodespaceWakeOutcome.Ready -> report("event=wake_ready backend=${outcome.backend} " +
                "route_ready=${outcome.routeReady} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            is CodespaceWakeOutcome.Failed -> report("event=wake_failed backend=${outcome.backend} " +
                "http=${outcome.httpCode} control_plane_unavailable=${outcome.controlPlaneUnavailable} " +
                "retry_allowed=${outcome.retryAllowed} retry_after_ms=${outcome.retryAfterMs} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            is CodespaceWakeOutcome.TimedOut -> report("event=wake_timeout backend=${outcome.backend} " +
                "retry_after_ms=${outcome.retryAfterMs} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
        assertTrue("The selected account could not wake its Codespace on the physical network",
            outcome is CodespaceWakeOutcome.Ready)
    }

    private fun selectedWakeRequest(context: G2RayApplication): CodespaceWakeRequest {
        val repository = context.accountRepository
        assertTrue("Saved data failed to load", repository.loadError.value == null)
        val selection = repository.activeSelection.value
        val account = repository.accounts.value.firstOrNull { it.id == selection.accountId }
            ?: error("Select a Codespace account before this test")
        val workerKey = repository.apiKeys.value.firstOrNull {
            it.id == selection.apiKeyId && it.accountId == account.id && it.enabled &&
                it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        } ?: repository.enabledApiKey(account.id, ApiKeyKind.WORKER_WAKE_SECRET)
        val githubKey = repository.enabledApiKey(account.id, ApiKeyKind.GITHUB_TOKEN)
        val controlPlane = repository.workerControlPlane.value
        val mode = InstrumentationRegistry.getArguments().getString("wakeBackend")?.let {
            CodespaceWakeMode.valueOf(it)
        } ?: account.wakeMode
        return CodespaceWakeRequest(
            mode = mode,
            githubCodespaceName = account.codespaceName.orEmpty(),
            workerCodespaceId = account.workerCodespaceId ?: account.codespaceName,
            workerBaseUrls = controlPlane?.workerBaseUrls()?.takeIf { it.isNotEmpty() }
                ?: account.workerBaseUrls(),
            workerSecret = controlPlane?.encryptedWakeSecret?.takeIf { it.isNotBlank() }
                ?: workerKey?.encryptedValue,
            githubToken = githubKey?.encryptedValue
        )
    }

    @Test
    fun selectedProfileCarriesTrafficAndRecoversAfterBackgroundIdle() = runLiveTest()

    @Test
    fun selectedProfileStartsThroughUiAndSurvivesIdle() = runLiveTest(uiStart = true)

    @Test
    fun selectedAccountControlPlaneWorksWhileVpnIsActive() = runLiveTest(controlPlaneCheck = true)

    @Test
    fun selectedProfileStartsWithSmallProbes() = runLiveTest(startupOnly = true)

    @Test
    fun selectedProfileRecoversAfterRepeatedCoreFailures() {
        assumeTrue(
            "Core failure injection requires -e liveCoreRecovery true",
            InstrumentationRegistry.getArguments().getString("liveCoreRecovery") == "true"
        )
        runLiveTest(coreFailureCycles = 2)
    }

    private fun engineFromTestArgument(value: String?): VpnEngineMode {
        val storageValue = value ?: VpnEngineMode.Hev.storageValue
        return requireNotNull(VpnEngineMode.entries.firstOrNull { it.storageValue == storageValue }) {
            "Test engine must be auto, hev, or xray_native"
        }
    }

    private fun runLiveTest(
        coreFailureCycles: Int = 0,
        uiStart: Boolean = false,
        controlPlaneCheck: Boolean = false,
        startupOnly: Boolean = false
    ) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live network tests require -e liveVpn true", args.getString("liveVpn") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        assertTrue("Grant VPN permission in the app before this test", VpnService.prepare(context) == null)
        val repository = context.accountRepository
        assertTrue("Saved data failed to load", repository.loadError.value == null)
        val selection = repository.activeSelection.value
        val selected = repository.profiles.value.firstOrNull { it.id == selection.profileId }
        assertNotNull("Select a working profile in the app before this test", selected)
        val transport = args.getString("transport")?.let { value ->
            Transport.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Test transport must be xhttp or ws")
        }
        val profile = if (transport == null || transport == selected!!.transport) selected else {
            repository.profiles.value.firstOrNull {
                it.accountId == selected!!.accountId && it.address == selected.address && it.transport == transport
            }
        }
        assertNotNull("No matching saved transport profile exists on the selected edge", profile)
        assertTrue("UI startup uses the selected profile, not a transport override",
            !uiStart || transport == null || transport == selected!!.transport)
        val settings = VpnEnginePreferences(context.secureStore).readSettings()
        val mode = if (uiStart) settings.mode else {
            engineFromTestArgument(args.getString("engine"))
        }
        report("event=startup_settings fast_dns=${settings.fastDnsEnabled} ipv4_only=${settings.ipv4OnlyEnabled} " +
            "dpi_compatibility=${settings.dpiCompatibilityEnabled} seamless_failover=${settings.seamlessFailoverEnabled}")
        val idleSeconds = (args.getString("idleSeconds")?.toLongOrNull() ?: 60L).coerceIn(60L, 600L)
        val resumeVpnActivity = args.getString("resumeVpnActivity") != "false"
        val smallProbesOnly = args.getString("smallProbesOnly") == "true"
        require(!startupOnly || smallProbesOnly) { "Startup timing tests require -e smallProbesOnly true" }
        val screenOff = args.getString("screenOff") == "true"
        require(!screenOff || !resumeVpnActivity) {
            "Controlled screen-off tests require -e resumeVpnActivity false"
        }
        val client = OkHttpClient.Builder()
            // No SOCKS proxy, socket protection, or underlying-network binding:
            // these requests must traverse Android's default full-device VPN.
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    report("event=connection_acquired endpoint=${call.request().url.host} " +
                        "protocol=${connection.protocol()} connection=${System.identityHashCode(connection)}")
                }
            })
            .build()

        var scenario = ActivityScenario.launch(MainActivity::class.java)
        var startRequested = false
        var uiViewModel: G2RayViewModel? = null
        val lifecycleTrace = launch(Dispatchers.Default) {
            VpnStateRepository.state
                .map { Triple(it.tunnelState, it.tunnelVerified, it.activeVpnEngine) to startupPhase(it.message) }
                .distinctUntilChanged()
                .collect { (stateInfo, phase) ->
                    val (state, verified, engine) = stateInfo
                    report("event=tunnel_state state=$state verified=$verified engine=$engine " +
                        "phase=$phase elapsed_ms=${SystemClock.elapsedRealtime()}")
                }
        }
        try {
            val startedAt = SystemClock.elapsedRealtime()
            scenario.onActivity { activity ->
                if (uiStart) {
                    uiViewModel = ViewModelProvider(activity)[G2RayViewModel::class.java]
                    uiViewModel!!.startVpn(activity)
                } else {
                    ContextCompat.startForegroundService(activity, Intent(activity, G2RayVpnService::class.java).apply {
                        action = G2RayVpnService.ACTION_START
                        putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, profile!!.rawUri)
                        putExtra(G2RayVpnService.EXTRA_ACCOUNT_ID, selection.accountId)
                        putExtra(G2RayVpnService.EXTRA_VPN_ENGINE_MODE, mode.storageValue)
                    })
                }
                startRequested = true
            }
            val connected = withTimeout(180_000L) {
                VpnStateRepository.state.first {
                    (it.tunnelState == TunnelState.Connected && it.tunnelVerified) || it.tunnelState == TunnelState.Error
                }
            }
            assertEquals("Tunnel failed to verify", TunnelState.Connected, connected.tunnelState)
            val expectedEngine = if (mode == VpnEngineMode.XrayNative) ActiveVpnEngine.XrayNative else ActiveVpnEngine.Hev
            assertEquals("The requested engine was not exercised", expectedEngine, connected.activeVpnEngine)
            report("engine=${mode.storageValue} transport=${profile!!.transport} ui_start=$uiStart " +
                "verified_start_ms=${SystemClock.elapsedRealtime() - startedAt}")

            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            assertTrue(
                "Default network is not the VPN",
                connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            )
            assertResponsiveQuorum(probeHttpsQuorum(client, "initial"), "initial")
            if (smallProbesOnly) {
                report("transfer_sample_skipped=true small_probes_only=true")
            } else {
                transferSample(client)
            }
            if (expectedEngine == ActiveVpnEngine.Hev) {
                val minimumRxBytes = if (smallProbesOnly) 1L else DOWNLOAD_BYTES.toLong()
                val counters = withTimeout(15_000L) {
                    VpnStateRepository.state.first {
                        it.tunnelTrafficSource == TunnelTrafficSource.HevTun && it.totalRxBytes >= minimumRxBytes
                    }
                }
                assertTrue("Real TUN upload counters did not advance", counters.totalTxBytes > 0L)
                report("hev_tun_rx_bytes=${counters.totalRxBytes} hev_tun_tx_bytes=${counters.totalTxBytes}")
            } else {
                assertEquals(TunnelTrafficSource.Unavailable, VpnStateRepository.state.value.tunnelTrafficSource)
            }

            if (startupOnly) {
                report("startup_only_acceptance=true")
                return@runBlocking
            }
            if (controlPlaneCheck) {
                verifyActiveControlPlane(context)
                return@runBlocking
            }
            if (coreFailureCycles > 0) {
                repeatCoreFailureRecovery(client, coreFailureCycles)
                return@runBlocking
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (screenOff) {
                ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("input keyevent KEYCODE_SLEEP")
                ).use { output ->
                    while (output.read() != -1) { /* Drain the completed shell command. */ }
                }
                withTimeout(5_000L) {
                    while (power.isInteractive) delay(50L)
                }
            }
            val idleStartedAt = SystemClock.elapsedRealtime()
            report("background_idle_seconds=$idleSeconds device_interactive=${power.isInteractive} " +
                "controlled_screen_off=$screenOff")
            waitForBackgroundIdle(idleSeconds * 1_000L, power.takeIf { screenOff })
            val afterIdle = VpnStateRepository.state.value
            assertEquals("Service lost the VPN during background idle", TunnelState.Connected, afterIdle.tunnelState)
            // Reopen through Android's normal activity launch path. Trying to
            // force a stopped test activity to RESUMED after screen-off can
            // fail in the harness without demonstrating any VPN failure.
            if (resumeVpnActivity) {
                scenario.close()
                scenario = ActivityScenario.launch(MainActivity::class.java)
            }
            assertTrue(
                "Default network left the VPN after idle or activity recreation",
                connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            )
            report("idle_finished_device_interactive=${power.isInteractive} " +
                "resumed_vpn_activity=$resumeVpnActivity " +
                "actual_idle_gap_ms=${SystemClock.elapsedRealtime() - idleStartedAt}")
            val retainedResponsive = probeHttpsQuorum(client, "idle_resume_retained_pool")
            client.connectionPool.evictAll()
            val freshResponsive = probeHttpsQuorum(client, "idle_resume_fresh_socket")
            assertResponsiveQuorum(retainedResponsive, "idle_resume_retained_pool")
            assertResponsiveQuorum(freshResponsive, "idle_resume_fresh_socket")
            report("idle_resume_success=true")
        } finally {
            try {
                if (startRequested) {
                    // Cleanup must not depend on restoring a foreground UI.
                    if (uiViewModel != null) {
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            uiViewModel!!.stopVpn(context)
                        }
                    } else {
                        context.startService(Intent(context, G2RayVpnService::class.java).apply {
                            action = G2RayVpnService.ACTION_STOP
                        })
                    }
                    withTimeout(15_000L) {
                        VpnStateRepository.state.first { it.tunnelState == TunnelState.Disconnected }
                    }
                }
            } finally {
                try {
                    scenario.close()
                } finally {
                    lifecycleTrace.cancelAndJoin()
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun verifyActiveControlPlane(context: G2RayApplication) {
        val service = SocketProtector.vpnService ?: error("The test VPN service is not active")
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val physical = connectivity.allNetworks.firstOrNull {
            val capabilities = connectivity.getNetworkCapabilities(it)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } ?: error("No physical network is available")
        Socket().use { socket ->
            report("event=socket_descriptor_probe protect_before_materialization=" +
                runCatching { service.protect(socket) }.getOrDefault(false))
        }
        Socket().use { socket ->
            physical.bindSocket(socket)
            report("event=socket_descriptor_probe protect_after_network_bind=${service.protect(socket)}")
        }
        Socket().use { socket ->
            assertTrue("The production recovery socket was rejected before connect",
                SocketProtector.protectControlPlane(socket))
            assertTrue("Protection unexpectedly connected the recovery socket", !socket.isConnected)
        }
        val coordinator = CodespaceWakeCoordinator(
            WorkerRouteCoordinator(WorkerNetwork.createApiService(
                loggingEnabled = false,
                protectSocket = SocketProtector::protectControlPlane,
                dnsLookup = SocketProtector::lookupControlPlane
            )),
            GitHubCodespaceCoordinator(GitHubNetwork.createApiService(
                loggingEnabled = false,
                protectSocket = SocketProtector::protectControlPlane,
                dnsLookup = SocketProtector::lookupControlPlane
            ))
        )
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = coordinator.ensureReady(selectedWakeRequest(context), maxWaitMs = 30_000L)
        report("event=active_control_plane outcome=${outcome.javaClass.simpleName} " +
            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        assertTrue("Recovery could not contact its backend while the VPN was active",
            outcome is CodespaceWakeOutcome.Ready)
    }

    private suspend fun repeatCoreFailureRecovery(client: OkHttpClient, cycles: Int) =
        kotlinx.coroutines.coroutineScope {
            repeat(cycles) { index ->
                val cycle = index + 1
                val startedAt = SystemClock.elapsedRealtime()
                // Stop the actual native core, not the service or user intent.
                // A second recovery demonstrates that health monitoring survives
                // the first, while the HTTPS checks prove a working data path.
                val disconnected = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(60_000L) {
                        VpnStateRepository.state.first {
                            it.tunnelState != TunnelState.Connected || !it.tunnelVerified
                        }
                    }
                }
                val bridge = ReflectionXrayBridge()
                assertTrue("Could not inject core failure $cycle", bridge.stop().success)
                assertTrue("Native core did not stop for failure $cycle", !bridge.getState())
                report("event=core_failure_injected cycle=$cycle")
                disconnected.await()
                val reconnected = withTimeout(180_000L) {
                    VpnStateRepository.state.first {
                        (it.tunnelState == TunnelState.Connected && it.tunnelVerified) ||
                            it.tunnelState == TunnelState.Error
                    }
                }
                assertEquals("Core failure $cycle did not recover", TunnelState.Connected, reconnected.tunnelState)
                client.connectionPool.evictAll()
                assertResponsiveQuorum(probeHttpsQuorum(client, "core_recovery_$cycle"), "core_recovery_$cycle")
                report("event=core_recovery_verified cycle=$cycle " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }

    private suspend fun waitForBackgroundIdle(durationMs: Long, screenOffPower: PowerManager? = null) {
        // Coroutine timer delays exclude deep sleep on Android. Measure idle
        // with elapsedRealtime so screen-off time counts without a wake lock.
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (true) {
            assertTrue("Screen woke during the controlled screen-off interval", screenOffPower?.isInteractive != true)
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return
            delay(minOf(remainingMs, 1_000L))
        }
    }

    private fun probeHttpsQuorum(client: OkHttpClient, phase: String): Int {
        val endpoints = listOf(
            "https://www.gstatic.com/generate_204" to 204,
            "https://cp.cloudflare.com/generate_204" to 204,
            "https://example.com/" to 200
        )
        var responsive = 0
        for ((url, status) in endpoints) {
            val started = SystemClock.elapsedRealtime()
            try {
                client.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build())
                    .execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val valid = response.code == status && (status == 204 || body.contains("Example Domain"))
                        val elapsedMs = SystemClock.elapsedRealtime() - started
                        val fast = valid && elapsedMs <= RESPONSIVE_ENDPOINT_LIMIT_MS
                        if (fast) responsive++
                        report("phase=$phase endpoint=${url.substringAfter("https://").substringBefore('/')} " +
                            "http=${response.code} usable=$valid responsive=$fast elapsed_ms=$elapsedMs")
                    }
            } catch (error: Exception) {
                report("phase=$phase endpoint=${url.substringAfter("https://").substringBefore('/')} " +
                    "error=${error.javaClass.simpleName} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            }
        }
        return responsive
    }

    private fun assertResponsiveQuorum(responsive: Int, phase: String) {
        assertTrue(
            "Only $responsive/3 full-VPN HTTPS endpoints completed within " +
                "$RESPONSIVE_ENDPOINT_LIMIT_MS ms during $phase",
            responsive >= 2
        )
    }

    private fun transferSample(client: OkHttpClient) {
        val nonce = SystemClock.elapsedRealtimeNanos()
        val started = SystemClock.elapsedRealtime()
        client.newCall(Request.Builder()
            .url("https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES&nonce=$nonce")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .build()).execute().use { response ->
                assertEquals("Download endpoint rejected the transfer", 200, response.code)
                assertEquals("Download body was truncated", DOWNLOAD_BYTES, response.body?.bytes()?.size ?: 0)
            }
        report("download_bytes=$DOWNLOAD_BYTES elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        val uploadStarted = SystemClock.elapsedRealtime()
        val payload = ByteArray(UPLOAD_BYTES) { (it and 0xff).toByte() }
        client.newCall(Request.Builder()
            .url("https://speed.cloudflare.com/__up?nonce=$nonce")
            .post(payload.toRequestBody("application/octet-stream".toMediaType()))
            .build()).execute().use { response ->
                assertTrue("Upload endpoint rejected the transfer: HTTP ${response.code}", response.isSuccessful)
            }
        report("upload_bytes=$UPLOAD_BYTES elapsed_ms=${SystemClock.elapsedRealtime() - uploadStarted}")
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "\nLIVE_VPN $message\n")
        })
    }

    private fun startupPhase(message: String): String = when {
        message.startsWith("Contacting Codespace Worker") -> "worker_contact"
        message.startsWith("Worker is unreachable") -> "direct_fallback"
        message.startsWith("Codespace route is settling") -> "route_settling"
        message.startsWith("Checking Codespace route") -> "route_check"
        message.startsWith("Checking Codespace state with GitHub") -> "direct_check"
        message.startsWith("Starting Codespace through GitHub") -> "direct_start"
        message.startsWith("Waiting for GitHub") -> "direct_settling"
        message.startsWith("No underlying network") -> "network_wait"
        message.contains("Retrying automatically") -> "retry_backoff"
        message.contains("Recovering...") -> "initial_route_failure"
        message.startsWith("Checking config route") -> "initial_route_check"
        message.startsWith("The Android packet path stalled") -> "packet_engine_repair"
        else -> "other"
    }

    private companion object {
        const val DOWNLOAD_BYTES = 2 * 1024 * 1024
        const val UPLOAD_BYTES = 64 * 1024
        // A generous regression ceiling, not a promise of instant browsing.
        const val RESPONSIVE_ENDPOINT_LIMIT_MS = 6_000L
    }
}
