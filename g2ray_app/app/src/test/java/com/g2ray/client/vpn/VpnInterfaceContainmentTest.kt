package com.g2ray.client.vpn

import android.content.Context
import android.content.ContextWrapper
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/** Records the real service's Builder calls; Android routing still needs a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 31], shadows = [VpnInterfaceContainmentTest.BuilderShadow::class])
class VpnInterfaceContainmentTest {
    @Test
    fun defaultInterfaceCapturesBothFamiliesWithoutAppOrNetworkBypasses() {
        interfaceConfig(ipv4Only = false).use { snapshot ->
            assertEquals(setOf("0.0.0.0/0", "::/0"), snapshot.routes.toSet())
            assertEquals(listOf("1.1.1.1", "8.8.8.8"), snapshot.dns)
            assertFalse(snapshot.bypass)
            assertTrue(snapshot.allowedApps.isEmpty())
            assertTrue(snapshot.disallowedApps.isEmpty())
            assertTrue(snapshot.allowedFamilies.isEmpty())
        }
    }

    @Test
    fun ipv4CompatibilityDoesNotUnblockAnUnroutedIpv6Family() {
        interfaceConfig(ipv4Only = true).use { snapshot ->
            assertEquals(listOf("0.0.0.0/0"), snapshot.routes)
            assertEquals(listOf("198.18.0.1/15"), snapshot.addresses)
            // VpnService blocks families with no address/route/DNS. An
            // allowFamily(AF_INET6) call would instead permit fall-through.
            assertTrue(snapshot.allowedFamilies.isEmpty())
            assertFalse(snapshot.bypass)
        }
    }

    @Test
    fun explicitAppExclusionsNeverExcludeTheVpnItselfOrEnableGlobalBypass() {
        val ownPackage = RuntimeEnvironment.getApplication().packageName
        val valid = "com.example.excluded"
        val missing = "com.example.uninstalled"
        for (ipv4 in listOf(false, true)) {
            for (exclusions in listOf(emptySet(), setOf(valid), setOf(ownPackage, valid), setOf(missing, valid))) {
                interfaceConfig(ipv4, RoutingPolicy(excludedPackages = exclusions)).use { snapshot ->
                    assertEquals(exclusions - ownPackage - missing, snapshot.disallowedApps.toSet())
                    assertTrue(snapshot.allowedApps.isEmpty())
                    assertFalse(snapshot.bypass)
                    assertTrue(snapshot.allowedFamilies.isEmpty())
                    assertEquals(if (ipv4) setOf("0.0.0.0/0") else setOf("0.0.0.0/0", "::/0"), snapshot.routes.toSet())
                }
            }
        }
    }

    private fun interfaceConfig(ipv4Only: Boolean, routing: RoutingPolicy = RoutingPolicy()): Snapshot {
        val service = G2RayVpnService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, RuntimeEnvironment.getApplication())
        G2RayVpnService::class.java.getDeclaredField("ipv4Only").apply {
            isAccessible = true
            setBoolean(service, ipv4Only)
        }
        G2RayVpnService::class.java.getDeclaredField("routingPolicy").apply {
            isAccessible = true
            set(service, routing)
        }
        val snapshot = Snapshot()
        captured = snapshot
        try {
            val tun = G2RayVpnService::class.java.getDeclaredMethod("establishTunInterface")
                .apply { isAccessible = true }.invoke(service) as ParcelFileDescriptor
            assertTrue(tun === snapshot.pipe[0])
            return snapshot
        } catch (error: Throwable) {
            snapshot.close()
            throw error
        } finally {
            captured = null
        }
    }

    class Snapshot : AutoCloseable {
        val routes = mutableListOf<String>()
        val addresses = mutableListOf<String>()
        val dns = mutableListOf<String>()
        val allowedApps = mutableListOf<String>()
        val disallowedApps = mutableListOf<String>()
        val allowedFamilies = mutableListOf<Int>()
        var bypass = false
        val pipe = ParcelFileDescriptor.createPipe()
        override fun close() { pipe.forEach { it.close() } }
    }

    @Implements(VpnService.Builder::class)
    class BuilderShadow {
        @RealObject lateinit var real: VpnService.Builder

        @Implementation fun addRoute(address: String, prefix: Int): VpnService.Builder = real.also {
            checkNotNull(captured).routes += "$address/$prefix"
        }
        @Implementation fun addAddress(address: String, prefix: Int): VpnService.Builder = real.also {
            checkNotNull(captured).addresses += "$address/$prefix"
        }
        @Implementation fun addDnsServer(address: String): VpnService.Builder = real.also {
            checkNotNull(captured).dns += address
        }
        @Implementation fun allowBypass(): VpnService.Builder = real.also { checkNotNull(captured).bypass = true }
        @Implementation fun allowFamily(family: Int): VpnService.Builder = real.also { checkNotNull(captured).allowedFamilies += family }
        @Implementation fun addAllowedApplication(app: String): VpnService.Builder = real.also { checkNotNull(captured).allowedApps += app }
        @Implementation fun addDisallowedApplication(app: String): VpnService.Builder {
            if (app == "com.example.uninstalled") throw android.content.pm.PackageManager.NameNotFoundException(app)
            return real.also { checkNotNull(captured).disallowedApps += app }
        }
        @Implementation fun establish(): ParcelFileDescriptor = checkNotNull(captured).pipe[0]
    }

    companion object { private var captured: Snapshot? = null }
}
