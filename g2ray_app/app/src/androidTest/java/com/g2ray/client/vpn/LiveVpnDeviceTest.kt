package com.g2ray.client.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.g2ray.client.G2RayApplication
import com.g2ray.client.MainActivity
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.TunnelTrafficSource
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.model.VpnStateRepository
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveVpnDeviceTest {
    @Test
    fun selectedProfileCarriesTrafficAndRecoversAfterBackgroundIdle() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live network tests require -e liveVpn true", args.getString("liveVpn") == "true")
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        assertTrue("Grant VPN permission in the app before this test", VpnService.prepare(context) == null)
        val repository = context.accountRepository
        assertTrue("Saved data failed to load", repository.loadError.value == null)
        val selection = repository.activeSelection.value
        val profile = repository.profiles.value.firstOrNull { it.id == selection.profileId }
        assertNotNull("Select a working profile in the app before this test", profile)
        val mode = VpnEngineMode.fromStorage(args.getString("engine") ?: "hev")
        val idleSeconds = (args.getString("idleSeconds")?.toLongOrNull() ?: 60L).coerceIn(60L, 600L)
        val client = OkHttpClient.Builder()
            // No SOCKS proxy, socket protection, or underlying-network binding:
            // these requests must traverse Android's default full-device VPN.
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startRequested = false
        try {
            val startedAt = SystemClock.elapsedRealtime()
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, G2RayVpnService::class.java).apply {
                    action = G2RayVpnService.ACTION_START
                    putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, profile!!.rawUri)
                    putExtra(G2RayVpnService.EXTRA_ACCOUNT_ID, selection.accountId)
                    putExtra(G2RayVpnService.EXTRA_VPN_ENGINE_MODE, mode.storageValue)
                })
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
            report("engine=${mode.storageValue} verified_start_ms=${SystemClock.elapsedRealtime() - startedAt}")

            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            assertTrue(
                "Default network is not the VPN",
                connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            )
            probeHttpsQuorum(client, "initial")
            transferSample(client)
            if (expectedEngine == ActiveVpnEngine.Hev) {
                val counters = withTimeout(15_000L) {
                    VpnStateRepository.state.first {
                        it.tunnelTrafficSource == TunnelTrafficSource.HevTun && it.totalRxBytes >= DOWNLOAD_BYTES
                    }
                }
                assertTrue("Real TUN upload counters did not advance", counters.totalTxBytes > 0L)
                report("hev_tun_rx_bytes=${counters.totalRxBytes} hev_tun_tx_bytes=${counters.totalTxBytes}")
            } else {
                assertEquals(TunnelTrafficSource.Unavailable, VpnStateRepository.state.value.tunnelTrafficSource)
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            report("background_idle_seconds=$idleSeconds")
            delay(idleSeconds * 1_000L)
            val afterIdle = VpnStateRepository.state.value
            assertEquals("Service lost the VPN during background idle", TunnelState.Connected, afterIdle.tunnelState)
            scenario.moveToState(Lifecycle.State.RESUMED)
            client.connectionPool.evictAll()
            probeHttpsQuorum(client, "idle_resume_fresh_socket")
            report("idle_resume_success=true")
        } finally {
            if (startRequested) {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    activity.startService(Intent(activity, G2RayVpnService::class.java).apply {
                        action = G2RayVpnService.ACTION_STOP
                    })
                }
                withTimeout(15_000L) {
                    VpnStateRepository.state.first { it.tunnelState == TunnelState.Disconnected }
                }
            }
            scenario.close()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun probeHttpsQuorum(client: OkHttpClient, phase: String) {
        val endpoints = listOf(
            "https://www.gstatic.com/generate_204" to 204,
            "https://cp.cloudflare.com/generate_204" to 204,
            "https://example.com/" to 200
        )
        var passed = 0
        for ((url, status) in endpoints) {
            val started = SystemClock.elapsedRealtime()
            try {
                client.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build())
                    .execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val valid = response.code == status && (status == 204 || body.contains("Example Domain"))
                        if (valid) passed++
                        report("phase=$phase endpoint=${url.substringAfter("https://").substringBefore('/')} " +
                            "http=${response.code} usable=$valid elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                    }
            } catch (error: Exception) {
                report("phase=$phase endpoint=${url.substringAfter("https://").substringBefore('/')} " +
                    "error=${error.javaClass.simpleName} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            }
        }
        assertTrue("Only $passed/3 full-VPN HTTPS endpoints passed during $phase", passed >= 2)
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

    private companion object {
        const val DOWNLOAD_BYTES = 2 * 1024 * 1024
        const val UPLOAD_BYTES = 64 * 1024
    }
}
