package com.g2ray.client.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Build
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
import com.g2ray.client.parser.VlessUriParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertFalse
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
    fun experimentalRouteComparedWithoutChangingSavedConfiguration() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Experimental routes require -e liveExperimentalRoute true",
            args.getString("liveExperimentalRoute") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        val repository = context.accountRepository
        assertTrue("Saved data failed to load", repository.loadError.value == null)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun requirePhysicalNetwork() {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            assertTrue("A working physical network is required",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            assertFalse("Stop VPN before comparing physical routes",
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        }
        requirePhysicalNetwork()
        val selection = repository.activeSelection.value
        val settings = VpnEnginePreferences(context.secureStore).readSettings()
        // A shell-readable test file avoids placing the VLESS credential in
        // instrumentation arguments, logcat, or the saved profile repository.
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat /data/local/tmp/g2ray-experimental-route.vless"
            )
        ).use { input ->
            val buffer = ByteArray(16_385)
            var length = 0
            while (length < buffer.size) {
                val count = input.read(buffer, length, buffer.size - length)
                if (count < 0) break
                length += count
            }
            buffer.copyOf(length)
        }
        require(bytes.size <= 16_384) { "Experimental route file is too large" }
        val experimentalText = bytes.toString(Charsets.UTF_8).trim()
        require(experimentalText.isNotEmpty()) {
            "Push an owned VLESS link to /data/local/tmp/g2ray-experimental-route.vless before running"
        }
        val experimental = VlessUriParser.parse(experimentalText).getOrThrow()
        val candidates = repository.profiles.value.mapNotNull { stored ->
            VlessUriParser.parse(stored.rawUri).getOrNull()?.takeIf { it.uuid == experimental.uuid }
        }
        val direct = candidates.firstOrNull {
            it.transport == experimental.transport && it.alpn == experimental.alpn &&
                it.address != experimental.address
        } ?: error("Import a matching direct profile before comparing an experimental route")
        val manager = XrayCoreManager()
        val results = mutableListOf<Pair<String, XrayDelaySummary>>()
        withTimeout(180_000L) {
            withContext(Dispatchers.IO) {
                listOf("direct" to direct, "experimental" to experimental,
                    "experimental" to experimental, "direct" to direct).forEach { (label, profile) ->
                    requirePhysicalNetwork()
                    val result = manager.measureDelaySummary(
                        profile, context.filesDir.absolutePath, timeoutSeconds = 4,
                        testUrls = SocksRunningRouteProbe.HEALTH_URLS, sampleCount = 3,
                        dpiCompatibilityEnabled = settings.dpiCompatibilityEnabled,
                        checkActive = { coroutineContext.ensureActive() }
                    )
                    results += label to result
                    report("event=experimental_route_comparison route=$label " +
                        "successful=${result.successfulSamples} total=${result.totalSamples} " +
                        "median_ms=${result.medianMs} p95_ms=${result.p95Ms}")
                }
            }
        }
        assertEquals("Route comparison changed the active selection", selection,
            repository.activeSelection.value)
        assertEquals("Route comparison changed settings", settings,
            VpnEnginePreferences(context.secureStore).readSettings())
        assertFalse("Route comparison left a core running", ReflectionXrayBridge().getState())
        assertTrue("A direct comparison round failed its health quorum",
            results.filter { it.first == "direct" }.all { it.second.successfulSamples >= 2 })
        assertTrue("The experimental route failed its health quorum on the phone",
            results.filter { it.first == "experimental" }.all { it.second.successfulSamples >= 2 })
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
    fun optionalDomainBypassUsesPhysicalExitWithoutChangingDefaultVpnRouting() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Bypass checks require -e liveRouting true and -e liveVpn true",
            args.getString("liveRouting") == "true" && args.getString("liveVpn") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        val preferences = VpnEnginePreferences(context.secureStore)
        val original = preferences.readSettings()
        val fakeDns = args.getString("routingFakeDns") == "true"
        try {
            preferences.setRoutingPolicy(RoutingPolicy(bypassDomains = listOf("www.cloudflare.com")))
            preferences.setFastDnsEnabled(fakeDns)
            runLiveTest(acceptanceCheck = { _, client ->
                withContext(Dispatchers.IO) {
                    val network = requireNotNull(SocketProtector.underlyingNetworks.value)
                    val url = "https://www.cloudflare.com/cdn-cgi/trace"
                    val direct = network.openConnection(java.net.URL(url)) as java.net.HttpURLConnection
                    val expectedIp = try {
                        direct.connectTimeout = 10_000; direct.readTimeout = 10_000
                        assertEquals("Physical trace endpoint unavailable", 200, direct.responseCode)
                        direct.inputStream.use(::traceIp)
                    } finally { direct.disconnect() }
                    val bypassIp = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        assertEquals("Bypass trace endpoint unavailable", 200, response.code)
                        requireNotNull(response.body).byteStream().use(::traceIp)
                    }
                    assertEquals("Domain rule did not use the physical exit", expectedIp, bypassIp)
                    report("optional_domain_bypass_verified=true fake_dns=$fakeDns physical_ip_match=true")
                }
            })
        } finally {
            preferences.setRoutingPolicy(original.routingPolicy)
            preferences.setFastDnsEnabled(original.fastDnsEnabled)
        }
    }

    private fun traceIp(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 16_384) { "Trace response exceeded its budget" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name()).lineSequence().firstOrNull { it.startsWith("ip=") }
            ?.removePrefix("ip=")?.trim()?.takeIf { it.isNotBlank() } ?: error("Trace response omitted its IP")
    }

    @Test
    fun selectedProfileCarriesBoundedTransferSamples() {
        assumeTrue("Larger transfers require -e liveTransfers true",
            InstrumentationRegistry.getArguments().getString("liveTransfers") == "true")
        runLiveTest(transferOnly = true)
    }

    @Test
    fun selectedProfilePlaysBoundedYoutubeSample() {
        assumeTrue("Third-party video tests require -e liveVideo true",
            InstrumentationRegistry.getArguments().getString("liveVideo") == "true")
        runLiveTest(videoOnly = true)
    }

    @Test
    fun transferReaderRejectsTruncatedAndOversizedBodies() {
        assertEquals(4, readTransferBody(ByteArrayInputStream(ByteArray(4)), 4))
        assertThrows(IOException::class.java) { readTransferBody(ByteArrayInputStream(ByteArray(3)), 4) }
        val oversized = ByteArrayInputStream(ByteArray(100))
        assertThrows(IOException::class.java) { readTransferBody(oversized, 4) }
        assertEquals("Oversized responses must stop after one extra byte", 95, oversized.available())
    }

    @Test
    fun youtubeObservationNeverCreditsAnUnobservedGapOrAStoppedPlayer() {
        val observation = PlayingObservation()
        observation.record(0L, true)
        observation.record(100L, true)
        assertEquals(100L, observation.observedMs)
        observation.record(10_000L, true)
        assertEquals("A suspended observer cannot certify uninterrupted playback", 100L, observation.observedMs)
        observation.record(10_100L, false)
        observation.record(10_200L, true)
        assertEquals("A stopped interval cannot count as playing", 100L, observation.observedMs)
        observation.record(10_300L, true)
        assertEquals(200L, observation.observedMs)
    }

    @Test
    fun youtubeLimitsKeepBudgetDefaultsAndRequireLongerPlaybackForExtendedRuns() {
        assertEquals(VideoTestLimits(60_000L, 12L * 1024 * 1024, 15_000L), videoTestLimits(Bundle()))
        val extended = Bundle().apply {
            putString("videoDurationSeconds", "180")
            putString("videoBudgetMiB", "256")
        }
        assertEquals(VideoTestLimits(180_000L, 256L * 1024 * 1024, 150_000L), videoTestLimits(extended))
        for ((key, values) in mapOf(
            "videoDurationSeconds" to listOf("", "invalid", "59", "601", "9223372036854775807"),
            "videoBudgetMiB" to listOf("", "invalid", "11", "2049", "9223372036854775807")
        )) {
            values.forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    videoTestLimits(Bundle().apply { putString(key, value) })
                }
            }
        }
        assertEquals(2_048L * 1024 * 1024,
            videoTestLimits(Bundle().apply { putString("videoBudgetMiB", "2048") }).mobileBudgetBytes)
    }

    @Test
    fun selectedProfileRecoversAfterRepeatedCoreFailures() {
        assumeTrue(
            "Core failure injection requires -e liveCoreRecovery true",
            InstrumentationRegistry.getArguments().getString("liveCoreRecovery") == "true"
        )
        runLiveTest(coreFailureCycles = 2)
    }

    @Test
    fun selectedProfileRecoversAfterMobileDataOutage() {
        assumeTrue("Physical network changes require -e liveNetworkRecovery true",
            InstrumentationRegistry.getArguments().getString("liveNetworkRecovery") == "true")
        assumeTrue("The on-device restoration script requires Android 12 or newer",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        runLiveTest(acceptanceCheck = ::verifyMobileDataOutage)
    }

    @Test
    fun selectedProfileKeepsResponsiveDuringConcurrentTransfers() {
        assumeTrue("Loaded transfers require -e liveLoadedTransfers true",
            InstrumentationRegistry.getArguments().getString("liveLoadedTransfers") == "true")
        runLiveTest(acceptanceCheck = { _, client -> verifyLoadedTransfers(client) })
    }

    @Test
    fun deviceShellScriptExecutesMultipleCommandsThroughStdin() {
        assumeTrue("Bidirectional shell commands require Android 12 or newer",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assertEquals("first\nsecond\n", executeOnDeviceScript("printf 'first\\n'\nprintf 'second\\n'"))
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
        startupOnly: Boolean = false,
        transferOnly: Boolean = false,
        videoOnly: Boolean = false,
        acceptanceCheck: (suspend (Context, OkHttpClient) -> Unit)? = null
    ) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live network tests require -e liveVpn true", args.getString("liveVpn") == "true")
        val mobileRxBefore = TrafficStats.getMobileRxBytes()
        val mobileTxBefore = TrafficStats.getMobileTxBytes()
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
        require(!transferOnly || !smallProbesOnly) { "Transfer tests cannot run with smallProbesOnly=true" }
        require(!videoOnly || smallProbesOnly) { "Video tests require smallProbesOnly=true to reserve their budget" }
        val downloadBytes = if (transferOnly) BOUNDED_DOWNLOAD_BYTES else DOWNLOAD_BYTES
        val uploadBytes = if (transferOnly) BOUNDED_UPLOAD_BYTES else UPLOAD_BYTES
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
                    (it.tunnelState == TunnelState.Connected && it.tunnelVerified) ||
                        it.tunnelState in setOf(TunnelState.Error, TunnelState.Blocked)
                }
            }
            assertEquals("Tunnel failed to verify: ${connected.message}", TunnelState.Connected, connected.tunnelState)
            val actualEngine = requireNotNull(connected.activeVpnEngine) { "Verified VPN has no active engine" }
            if (mode != VpnEngineMode.Auto) {
                val requestedEngine = if (mode == VpnEngineMode.XrayNative) ActiveVpnEngine.XrayNative else ActiveVpnEngine.Hev
                assertEquals("The requested engine was not exercised", requestedEngine, actualEngine)
            }
            report("engine=${mode.storageValue} transport=${profile!!.transport} ui_start=$uiStart " +
                "actual_engine=$actualEngine verified_start_ms=${SystemClock.elapsedRealtime() - startedAt}")

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
                transferSample(client, downloadBytes, uploadBytes)
            }
            if (actualEngine == ActiveVpnEngine.Hev) {
                val minimumRxBytes = if (smallProbesOnly) 1L else downloadBytes.toLong()
                val counters = withTimeout(15_000L) {
                    VpnStateRepository.state.first {
                        it.tunnelTrafficSource == TunnelTrafficSource.HevTun && it.totalRxBytes >= minimumRxBytes
                    }
                }
                assertTrue("Real TUN upload counters did not advance", counters.totalTxBytes > 0L)
                report("hev_tun_rx_bytes=${counters.totalRxBytes} hev_tun_tx_bytes=${counters.totalTxBytes}")
            } else {
                val nativeSource = VpnStateRepository.state.value.tunnelTrafficSource
                assertTrue("Native mode must not display HEV counters",
                    nativeSource in setOf(TunnelTrafficSource.NativeUid, TunnelTrafficSource.Unavailable))
                if (nativeSource == TunnelTrafficSource.NativeUid) {
                    val counters = withTimeout(15_000L) {
                        VpnStateRepository.state.first { it.totalRxBytes > 0L && it.totalTxBytes > 0L }
                    }
                    report("native_uid_rx_bytes=${counters.totalRxBytes} native_uid_tx_bytes=${counters.totalTxBytes} approximate=true")
                } else report("native_uid_counters_unavailable=true")
            }

            if (startupOnly) {
                report("startup_only_acceptance=true")
                return@runBlocking
            }
            if (transferOnly) {
                assertResponsiveQuorum(probeHttpsQuorum(client, "after_transfer"), "after_transfer")
                report("bounded_transfer_acceptance=true payload_budget_bytes=${downloadBytes + uploadBytes}")
                return@runBlocking
            }
            if (videoOnly) {
                playBoundedYoutubeSample(context)
                assertResponsiveQuorum(probeHttpsQuorum(client, "after_video"), "after_video")
                return@runBlocking
            }
            if (controlPlaneCheck) {
                verifyActiveControlPlane(context)
                return@runBlocking
            }
            if (acceptanceCheck != null) {
                acceptanceCheck(context, client)
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
                    val mobileRxAfter = TrafficStats.getMobileRxBytes()
                    val mobileTxAfter = TrafficStats.getMobileTxBytes()
                    if (mobileRxBefore >= 0L && mobileTxBefore >= 0L &&
                        mobileRxAfter >= mobileRxBefore && mobileTxAfter >= mobileTxBefore) {
                        report("event=mobile_budget_delta rx_bytes=${mobileRxAfter - mobileRxBefore} " +
                            "tx_bytes=${mobileTxAfter - mobileTxBefore} includes_other_apps=true")
                    } else {
                        report("event=mobile_budget_delta unavailable=true")
                    }
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
                            it.tunnelState in setOf(TunnelState.Error, TunnelState.Blocked)
                    }
                }
                assertEquals("Core failure $cycle did not recover: ${reconnected.message}",
                    TunnelState.Connected, reconnected.tunnelState)
                client.connectionPool.evictAll()
                assertResponsiveQuorum(probeHttpsQuorum(client, "core_recovery_$cycle"), "core_recovery_$cycle")
                report("event=core_recovery_verified cycle=$cycle " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun verifyMobileDataOutage(context: Context, client: OkHttpClient) =
        kotlinx.coroutines.coroutineScope {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun physicalInternet() = connectivity.allNetworks.mapNotNull { network ->
                connectivity.getNetworkCapabilities(network)?.takeIf {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                }
            }
            val physical = physicalInternet()
            assertTrue("This test requires LTE only, with no second Internet network",
                physical.isNotEmpty() && physical.all { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) })
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)
            ).bufferedReader().use { it.readText() }
            assertEquals("Refuse to change a radio that was not originally enabled",
                "1", shell("settings get global mobile_data").trim())
            val startedAt = SystemClock.elapsedRealtime()
            // Keep restoration in the on-device script and the non-cancellable cleanup.
            val radioCycle = async(Dispatchers.IO) {
                executeOnDeviceScript("svc data disable\nsleep 8\nsvc data enable")
            }
            try {
                withTimeout(20_000L) {
                    while (physicalInternet().isNotEmpty()) delay(100L)
                }
                report("event=physical_network_lost elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
                val unverified = withTimeout(8_000L) {
                    VpnStateRepository.state.first { !it.tunnelVerified }
                }
                assertTrue("Network loss discarded the user's connected intent",
                    unverified.tunnelState !in setOf(TunnelState.Disconnected, TunnelState.Disabled, TunnelState.Error))
                radioCycle.await()
                withTimeout(30_000L) {
                    while (physicalInternet().isEmpty()) delay(100L)
                }
                val restoredAt = SystemClock.elapsedRealtime()
                report("event=physical_network_returned clock_ms=$restoredAt " +
                    "elapsed_ms=${restoredAt - startedAt}")
                val recovered = withTimeout(90_000L) {
                    VpnStateRepository.state.first {
                        (it.tunnelState == TunnelState.Connected && it.tunnelVerified) ||
                            it.tunnelState in setOf(TunnelState.Error, TunnelState.Blocked)
                    }
                }
                assertEquals("VPN did not recover automatically after the LTE outage: ${recovered.message}",
                    TunnelState.Connected, recovered.tunnelState)
                report("event=physical_network_tunnel_verified " +
                    "after_network_return_ms=${SystemClock.elapsedRealtime() - restoredAt}")
                client.connectionPool.evictAll()
                assertResponsiveQuorum(probeHttpsQuorum(client, "physical_network_recovery"), "physical_network_recovery")
                report("event=physical_network_recovery_verified outage_command_ms=8000 " +
                    "after_network_return_ms=${SystemClock.elapsedRealtime() - restoredAt} " +
                    "total_ms=${SystemClock.elapsedRealtime() - startedAt}")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    shell("svc data enable")
                    assertEquals("The test did not restore mobile data",
                        "1", shell("settings get global mobile_data").trim())
                }
            }
        }

    private fun executeOnDeviceScript(script: String): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Bidirectional shell commands require Android 12 or newer"
        }
        val descriptors = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { output ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { input ->
                input.write((script + "\n").toByteArray(Charsets.UTF_8))
            }
            output.bufferedReader().readText()
        }
    }

    private data class RequestInterval(val startedMs: Long, val finishedMs: Long) {
        fun containsComplete(probe: RequestInterval): Boolean =
            finishedMs > startedMs && probe.finishedMs > probe.startedMs &&
                probe.startedMs >= startedMs && probe.finishedMs <= finishedMs
    }

    @Test
    fun loadedOverlapRequiresACompleteProbeInsideAnActualTransferRequest() {
        val transfer = RequestInterval(100L, 200L)
        assertTrue(transfer.containsComplete(RequestInterval(110L, 190L)))
        assertTrue(transfer.containsComplete(RequestInterval(100L, 200L)))
        assertFalse(transfer.containsComplete(RequestInterval(50L, 90L)))
        assertFalse(transfer.containsComplete(RequestInterval(210L, 250L)))
        assertFalse(transfer.containsComplete(RequestInterval(90L, 110L)))
        assertFalse(transfer.containsComplete(RequestInterval(190L, 210L)))
        assertFalse(transfer.containsComplete(RequestInterval(150L, 150L)))
        assertFalse(RequestInterval(100L, 100L).containsComplete(RequestInterval(100L, 100L)))
    }

    private suspend fun verifyLoadedTransfers(client: OkHttpClient) =
        kotlinx.coroutines.coroutineScope {
            val startedAt = SystemClock.elapsedRealtime()
            val transferStarted = CompletableDeferred<Unit>()
            val transfers = Collections.synchronizedList(mutableListOf<RequestInterval>())
            val responsiveProbes = mutableListOf<RequestInterval>()
            val workload = async(Dispatchers.IO) {
                transferStarted.complete(Unit)
                repeat(LOADED_TRANSFER_CHUNKS) { index ->
                    coroutineContext.ensureActive()
                    report("event=loaded_transfer_chunk chunk=${index + 1}")
                    transferSample(client, BOUNDED_DOWNLOAD_BYTES, BOUNDED_UPLOAD_BYTES) { start, end ->
                        transfers.add(RequestInterval(start, end))
                    }
                }
            }
            transferStarted.await()
            var quorumChecks = 0
            while (!workload.isCompleted) {
                val phase = "during_transfer_${quorumChecks + 1}"
                withContext(Dispatchers.IO) {
                    assertResponsiveQuorum(probeHttpsQuorum(client, phase) { start, end ->
                        responsiveProbes.add(RequestInterval(start, end))
                    }, phase)
                }
                quorumChecks++
                delay(250L)
            }
            workload.await()
            // A scheduled coroutine (or an unfinished quorum) does not prove overlap.
            // Require a complete usable, responsive request inside a completed bulk
            // request's lifetime. This proves request concurrency, not link saturation.
            val completedTransfers = synchronized(transfers) { transfers.toList() }
            val overlappedProbes = responsiveProbes.count { probe ->
                completedTransfers.any { it.containsComplete(probe) }
            }
            assertTrue("No complete responsive HTTPS probe occurred inside a bulk request", overlappedProbes > 0)
            report("event=loaded_transfer_acceptance checks=$quorumChecks overlapped_probes=$overlappedProbes " +
                "chunks=$LOADED_TRANSFER_CHUNKS " +
                "payload_bytes=${LOADED_TRANSFER_CHUNKS.toLong() * (BOUNDED_DOWNLOAD_BYTES + BOUNDED_UPLOAD_BYTES)} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
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

    private fun probeHttpsQuorum(
        client: OkHttpClient,
        phase: String,
        onResponsiveRequest: (Long, Long) -> Unit = { _, _ -> }
    ): Int {
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
                        val finished = SystemClock.elapsedRealtime()
                        val elapsedMs = finished - started
                        val fast = valid && elapsedMs <= RESPONSIVE_ENDPOINT_LIMIT_MS
                        if (fast) {
                            responsive++
                            onResponsiveRequest(started, finished)
                        }
                        report("phase=$phase endpoint=${url.substringAfter("https://").substringBefore('/')} " +
                            "http=${response.code} usable=$valid responsive=$fast elapsed_ms=$elapsedMs " +
                            "request_start_ms=$started request_end_ms=$finished")
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

    private fun transferSample(
        client: OkHttpClient,
        downloadBytes: Int,
        uploadBytes: Int,
        onCompletedRequest: (Long, Long) -> Unit = { _, _ -> }
    ) {
        // A failed transfer must not silently consume another copy of the budget.
        val transferClient = client.newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()
        val nonce = SystemClock.elapsedRealtimeNanos()
        val started = SystemClock.elapsedRealtime()
        transferClient.newCall(Request.Builder()
            .url("https://speed.cloudflare.com/__down?bytes=$downloadBytes&nonce=$nonce")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .build()).execute().use { response ->
                assertEquals("Download endpoint rejected the transfer", 200, response.code)
                val body = response.body ?: throw IOException("Download response has no body")
                body.byteStream().use { readTransferBody(it, downloadBytes) }
            }
        val downloadFinished = SystemClock.elapsedRealtime()
        onCompletedRequest(started, downloadFinished)
        report("download_bytes=$downloadBytes elapsed_ms=${downloadFinished - started} " +
            "request_start_ms=$started request_end_ms=$downloadFinished")
        val payload = ByteArray(uploadBytes) { (it and 0xff).toByte() }
        val uploadStarted = SystemClock.elapsedRealtime()
        transferClient.newCall(Request.Builder()
            .url("https://speed.cloudflare.com/__up?nonce=$nonce")
            .post(payload.toRequestBody("application/octet-stream".toMediaType()))
            .build()).execute().use { response ->
                assertTrue("Upload endpoint rejected the transfer: HTTP ${response.code}", response.isSuccessful)
            }
        val uploadFinished = SystemClock.elapsedRealtime()
        onCompletedRequest(uploadStarted, uploadFinished)
        report("upload_bytes=$uploadBytes elapsed_ms=${uploadFinished - uploadStarted} " +
            "request_start_ms=$uploadStarted request_end_ms=$uploadFinished")
    }

    private fun readTransferBody(input: InputStream, expectedBytes: Int): Int {
        require(expectedBytes > 0)
        var total = 0
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, expectedBytes - total + 1))
            if (count == -1) break
            total += count
            if (total > expectedBytes) throw IOException("Download response exceeded the byte budget")
        }
        if (total != expectedBytes) throw IOException("Download response was truncated: $total/$expectedBytes bytes")
        return total
    }

    private suspend fun playBoundedYoutubeSample(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Budgeted third-party playback requires Android 10 or newer")
        }
        val args = InstrumentationRegistry.getArguments()
        val limits = videoTestLimits(args)
        val uri = Uri.parse(requireNotNull(args.getString("videoUrl")) { "Pass an explicit public YouTube videoUrl" })
        require(uri.scheme == "https" && uri.host == "www.youtube.com" && uri.path == "/watch" &&
            !uri.getQueryParameter("v").isNullOrBlank()) { "videoUrl must be a public HTTPS YouTube watch URL" }
        val expectedTitle = requireNotNull(args.getString("videoTitle")) { "Pass the public video's expected title" }
        require(expectedTitle.isNotBlank())
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        assertTrue("Unlock the phone before third-party playback", power.isInteractive &&
            !(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked)
        fun mobileBytes(): Long {
            val rx = TrafficStats.getMobileRxBytes()
            val tx = TrafficStats.getMobileTxBytes()
            check(rx >= 0L && tx >= 0L) { "Mobile counters are unavailable; refuse unbudgeted video" }
            return rx + tx
        }
        val initialBytes = mobileBytes()
        val started = SystemClock.elapsedRealtime()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        var controller: android.media.session.MediaController? = null
        var firstPlayingAt: Long? = null
        var initialPosition: Long? = null
        var maximumPosition = 0L
        val playingObservation = PlayingObservation()
        var bufferingTransitions = 0
        var previousState: Int? = null
        var budgetReached = false
        report("event=youtube_limits duration_ms=${limits.durationMs} " +
            "mobile_budget_bytes=${limits.mobileBudgetBytes} required_playing_ms=${limits.requiredPlayingMs}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            while (SystemClock.elapsedRealtime() - started < limits.durationMs) {
                val elapsed = SystemClock.elapsedRealtime() - started
                val used = mobileBytes() - initialBytes
                check(used >= 0L) { "Mobile counters reset during video; stop rather than guess the budget" }
                if (used >= limits.mobileBudgetBytes) {
                    budgetReached = true
                    break
                }
                controller = manager.getActiveSessions(null).firstOrNull { it.packageName == "com.google.android.youtube" }
                    ?: controller
                val metadata = controller?.metadata
                val titleMatches = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?.contains(expectedTitle, ignoreCase = true) == true
                val playback = controller?.playbackState
                val state = playback?.state
                val playingLocally = titleMatches && state == PlaybackState.STATE_PLAYING &&
                    controller?.playbackInfo?.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
                playingObservation.record(elapsed, playingLocally)
                if (playingLocally) {
                    if (firstPlayingAt == null) {
                        firstPlayingAt = elapsed
                        initialPosition = playback?.position
                        report("event=youtube_first_play elapsed_ms=$elapsed")
                    }
                    maximumPosition = maxOf(maximumPosition, playback?.position ?: 0L)
                }
                if (firstPlayingAt != null && state == PlaybackState.STATE_BUFFERING && previousState != state) {
                    bufferingTransitions++
                }
                if (state != previousState) report("event=youtube_state state=$state title_matches=$titleMatches elapsed_ms=$elapsed")
                previousState = state
                delay(100L)
            }
        } finally {
            try {
                controller?.transportControls?.pause()
                // Stop only the explicitly launched video app, so it cannot
                // continue prefetching after the test or its budget guard ends.
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                    "am force-stop --user current com.google.android.youtube"
                )).use { output -> while (output.read() != -1) { /* Drain shell completion. */ } }
            } finally {
                automation.dropShellPermissionIdentity()
            }
            val usedBytes = runCatching { mobileBytes() - initialBytes }.getOrNull()
            report("event=youtube_sample elapsed_ms=${SystemClock.elapsedRealtime() - started} " +
                "mobile_bytes=$usedBytes budget_reached=$budgetReached " +
                "reported_position_delta_ms=${maximumPosition - (initialPosition ?: maximumPosition)} " +
                "observed_playing_ms=${playingObservation.observedMs} " +
                "buffering_transitions=$bufferingTransitions")
        }
        assertNotNull("The requested video never reported active playback", firstPlayingAt)
        assertTrue("The requested video did not report local Playing state for ${limits.requiredPlayingMs} observed ms",
            playingObservation.observedMs >= limits.requiredPlayingMs)
        assertTrue("YouTube reported rebuffering during the bounded sample", bufferingTransitions == 0)
        report("bounded_youtube_acceptance=true")
    }

    private data class VideoTestLimits(
        val durationMs: Long,
        val mobileBudgetBytes: Long,
        val requiredPlayingMs: Long
    )

    private fun videoTestLimits(args: Bundle): VideoTestLimits {
        fun argument(name: String, default: Long, allowed: LongRange): Long {
            val value = args.getString(name)?.let {
                requireNotNull(it.toLongOrNull()) { "$name must be an integer" }
            } ?: default
            require(value in allowed) { "$name must be in $allowed" }
            return value
        }
        val durationSeconds = argument("videoDurationSeconds", 60L, 60L..600L)
        val budgetMiB = argument("videoBudgetMiB", 12L, 12L..2_048L)
        // Preserve the limited-data check; extended runs require all but 30 s playing.
        val requiredSeconds = if (durationSeconds == 60L) 15L else durationSeconds - 30L
        return VideoTestLimits(durationSeconds * 1_000L, budgetMiB * 1024 * 1024, requiredSeconds * 1_000L)
    }

    private class PlayingObservation {
        var observedMs = 0L
            private set
        private var previousPlayingAt: Long? = null

        fun record(elapsedMs: Long, playing: Boolean) {
            // A MediaSession position is an update-time snapshot, not a frame
            // counter. Count only adjacent observations of local Playing state.
            val gap = previousPlayingAt?.let { elapsedMs - it }
            if (playing && gap != null && gap in 1L..1_500L) observedMs += gap
            previousPlayingAt = elapsedMs.takeIf { playing }
        }
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
        const val BOUNDED_DOWNLOAD_BYTES = 8 * 1024 * 1024
        const val BOUNDED_UPLOAD_BYTES = 2 * 1024 * 1024
        const val LOADED_TRANSFER_CHUNKS = 12
        // A generous regression ceiling, not a promise of instant browsing.
        const val RESPONSIVE_ENDPOINT_LIMIT_MS = 6_000L
    }
}
