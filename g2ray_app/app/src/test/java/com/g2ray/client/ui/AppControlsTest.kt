package com.g2ray.client.ui

import android.util.Base64
import com.g2ray.client.data.QuotaEstimate
import com.g2ray.client.data.QuotaEstimateStore
import com.g2ray.client.data.SecureKeyValueStore
import com.g2ray.client.data.quotaCycle
import com.g2ray.client.parser.ConfigImportPayload
import com.g2ray.client.parser.VlessUriParser
import com.g2ray.client.vpn.RoutingPolicy
import com.g2ray.client.vpn.VpnEnginePreferences
import com.g2ray.client.vpn.DiagnosticEvents
import com.g2ray.client.vpn.VpnTileService
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.TunnelState
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class AppControlsTest {
    private val uri = "vless://00000000-0000-0000-0000-000000000001@example-443.app.github.dev:443?encryption=none&security=tls&sni=example-443.app.github.dev&host=example-443.app.github.dev&type=xhttp&path=%2F&mode=packet-up&alpn=h2#example"

    private fun compress(text: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return ConfigImportPayload.PREFIX + Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Test fun qrRoundTripKeepsOriginalConfigBytes() {
        val bitmap = encodeConfigQr(uri)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(uri, decoded.text)
    }

    @Test fun emptyQrIsRejected() { assertTrue(runCatching { encodeConfigQr("") }.isFailure) }

    @Test fun compactBundleImportsEveryLinkAndAdvancedTuning() {
        val advanced = uri.replace("#example", "&extra=%7B%22xmux%22%3A%7B%22maxConcurrency%22%3A4%7D%7D#advanced")
        val json = JSONObject().put("schema_version", 2).put("configs", JSONArray(listOf(uri, advanced)))
        val parsed = VlessUriParser.parseAllProfiles(compress(json.toString()))
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.isSuccess })
        assertEquals(advanced, parsed.last().getOrThrow().rawUri)
        assertNotNull(parsed.last().getOrThrow().xhttpExtraJson)
        assertTrue(VlessUriParser.parseFirstProfile(compress(json.toString())).isSuccess)
    }

    @Test fun compactQrRejectsCorruptCompressedData() {
        assertTrue(VlessUriParser.parseFirstProfile(ConfigImportPayload.PREFIX + "not_gzip").isFailure)
        assertTrue(VlessUriParser.parseAllProfiles(ConfigImportPayload.PREFIX + "not_gzip").single().isFailure)
    }

    @Test fun compactQrRejectsDecompressionBomb() {
        assertTrue(runCatching { ConfigImportPayload.expand(compress("a".repeat(1_000_001))) }.isFailure)
    }

    @Test fun compactBundleLimitAndSchemaAreValidatedWithoutRecursion() {
        val unknown = JSONObject().put("schema_version", 99).put("configs", JSONArray(listOf(uri))).toString()
        assertTrue(VlessUriParser.parseAllProfiles(compress(unknown)).single().isFailure)
        val oversized = JSONObject().put("schema_version", 2).put("configs", JSONArray(List(501) { uri })).toString()
        assertTrue(VlessUriParser.parseAllProfiles(oversized).single().isFailure)
        val nested = JSONObject().put("schema_version", 2).put("configs", JSONArray(listOf("{\"configs\":[]}"))).toString()
        assertTrue(VlessUriParser.parseAllProfiles(nested).isEmpty())
    }

    @Test fun foreignJsonKeepsLegacyLinkExtractionWithoutClaimingBundleCompatibility() {
        for (version in listOf<Int?>(null, 99)) {
            val json = JSONObject().put("configs", JSONArray(listOf(uri)))
            version?.let { json.put("schema_version", it) }
            assertEquals(uri, VlessUriParser.parseFirstProfile(json.toString()).getOrThrow().rawUri)
            assertEquals(listOf(uri), VlessUriParser.parseAllProfiles(json.toString()).map { it.getOrThrow().rawUri })
        }
    }

    @Test fun compactQrRequiresARecognizedBundleRatherThanExtractingArbitraryText() {
        for (text in listOf(uri, "{\"schema_version\":2}", "not a bundle")) {
            assertTrue(VlessUriParser.parseFirstProfile(compress(text)).isFailure)
        }
    }

    @Test fun settingsDestinationsSurviveRoundTrip() {
        listOf(SettingsDest.Accounts, SettingsDest.Connection, SettingsDest.Routing, SettingsDest.Quota("a"),
            SettingsDest.AccountEditor(null), SettingsDest.AccountEditor("a"), SettingsDest.WorkerControlPlane,
            SettingsDest.ApiKeys("a"), SettingsDest.ApiKeyEditor("a", null), SettingsDest.ApiKeyEditor("a", "k"),
            SettingsDest.Profiles("a"), SettingsDest.ProfileImport("a")).forEach {
            assertEquals(it, decodeSettingsDestination(encodeSettingsDestination(it)))
        }
        assertNull(decodeSettingsDestination("unknown"))
        assertNull(decodeSettingsDestination("profiles|"))
    }

    @Test fun bypassDomainsValidateAndNormalizeInsteadOfAcceptingUrls() {
        assertEquals(listOf("example.ir", "example.com"), RoutingPolicy.parseDomains("*.Example.ir\nexample.com example.ir"))
        listOf("https://example.ir/path", "127.0.0.1", "example.ir/path", "-bad.ir", "example..ir", "localhost").forEach {
            assertTrue("Accepted $it", runCatching { RoutingPolicy.parseDomains(it) }.isFailure)
        }
        assertTrue(runCatching { RoutingPolicy.parseDomains((1..101).joinToString(" ") { "a$it.ir" }) }.isFailure)
    }

    @Test fun routingIsOptInAndPersistsAsOneTransaction() {
        val store = MemoryStore()
        val prefs = VpnEnginePreferences(store)
        assertEquals(RoutingPolicy(), prefs.getRoutingPolicy())
        val policy = RoutingPolicy(true, listOf("example.ir"), setOf("org.example.app"))
        prefs.setRoutingPolicy(policy)
        assertEquals(policy, VpnEnginePreferences(store).getRoutingPolicy())
        prefs.setRoutingPolicy(RoutingPolicy())
        assertEquals(RoutingPolicy(), prefs.getRoutingPolicy())
    }

    @Test fun corruptRoutingArraysFallBackToFullTunnelWithoutDiscardingStoredData() {
        for (key in listOf("vpn_bypass_domains", "vpn_excluded_packages")) {
            for (value in listOf("not json", "{}", "[null]", "[42]")) {
                val store = MemoryStore()
                store.putString("vpn_bypass_ir", "true")
                store.putString(key, value)
                assertEquals(RoutingPolicy(), VpnEnginePreferences(store).getRoutingPolicy())
                assertEquals(value, store.getString(key, null))
            }
        }
    }

    @Test fun invalidRoutingValuesCannotIntroduceBypassOrBreakStartup() {
        val invalid = mapOf(
            "vpn_bypass_domains" to listOf(JSONArray(listOf("https://example.ir/path")).toString(), JSONArray(List(101) { "example.ir" }).toString()),
            "vpn_excluded_packages" to listOf(JSONArray(listOf("bad/package")).toString(), JSONArray(List(201) { "org.example.app" }).toString())
        )
        for ((key, values) in invalid) for (value in values) {
            val store = MemoryStore()
            store.putString("vpn_bypass_ir", "true")
            store.putString(key, value)
            assertEquals(RoutingPolicy(), VpnEnginePreferences(store).getRoutingPolicy())
        }
    }

    @Test fun quotaProjectsOnlyWithinItsRecordedCycleAndNeverConfirmsABlock() {
        val now = 1_760_659_200_000L
        val store = QuotaEstimateStore(MemoryStore())
        val estimate = QuotaEstimate(59 * 3600L, 60 * 3600L, now, quotaCycle(now), true)
        store.save("one", estimate)
        assertEquals(59 * 3600L + 60, store.read("one")!!.projectedUsedSeconds(now + 60_000))
        assertNull(store.read("two"))
        assertFalse(store.isConfirmedBlocked("one", now))
        assertNull(estimate.projectedUsedSeconds(now - 1))
        assertNull(estimate.projectedUsedSeconds(now + 32L * 24 * 3600 * 1000))
        assertTrue(estimate.label(now + 2 * 3600_000).contains("0h 0m"))
    }

    @Test fun confirmedQuotaBlockDoesNotFollowAnotherAccountOrNextMonth() {
        val now = 1_760_659_200_000L
        val store = QuotaEstimateStore(MemoryStore())
        store.recordConfirmedBlock("one", now)
        assertTrue(store.isConfirmedBlocked("one", now))
        assertFalse(store.isConfirmedBlocked("two", now))
        assertFalse(store.isConfirmedBlocked("one", now + 32L * 24 * 3600 * 1000))
        store.clearConfirmedBlock("one")
        assertFalse(store.isConfirmedBlocked("one", now))
    }

    @Test fun quotaStoppedSnapshotDoesNotConsumeMoreHours() {
        val now = 1_760_659_200_000L
        assertEquals(3600L, QuotaEstimate(3600, 216000, now, quotaCycle(now), false).projectedUsedSeconds(now + 3600_000))
    }

    @Test fun quotaRejectsInvalidUntrustedSnapshots() {
        val store = QuotaEstimateStore(MemoryStore())
        assertTrue(runCatching { store.save("a", QuotaEstimate(-1, 216000, 1, "1970-01", false)) }.isFailure)
        assertTrue(runCatching { store.save("a", QuotaEstimate(1, 0, 1, "1970-01", false)) }.isFailure)
    }

    @Test fun quotaFieldsCannotRestampPriorMonthUsageOrSaveBeforeLoading() {
        val now = 1_760_659_200_000L
        val later = now + 32L * 24 * 3600 * 1000
        assertTrue(runCatching { quotaEstimateFromFields("59", "21", "60", true, quotaCycle(now), later) }.isFailure)
        assertTrue(runCatching { quotaEstimateFromFields("59", "21", "60", true, null, now) }.isFailure)
    }

    @Test fun quotaFieldsPreserveTheCurrentCycleAndValidateInputBounds() {
        val now = 1_760_659_200_000L
        val estimate = quotaEstimateFromFields("59", "21", "60", true, quotaCycle(now), now)
        assertEquals(59 * 3600L + 21 * 60, estimate.usedSeconds)
        assertEquals(60 * 3600L, estimate.budgetSeconds)
        assertEquals(quotaCycle(now), estimate.cycle)
        assertEquals(0L, quotaEstimateFromFields("0", "", "60", false, quotaCycle(now), now).usedSeconds)
        for ((hours, minutes, budget) in listOf(Triple("-1", "0", "60"), Triple("744", "1", "60"),
            Triple("0", "60", "60"), Triple("0", "0", "0"), Triple(Long.MAX_VALUE.toString(), "0", "60"))) {
            assertTrue(runCatching { quotaEstimateFromFields(hours, minutes, budget, true, quotaCycle(now), now) }.isFailure)
        }
    }

    @Test fun rejectedTileStopOpensTheAppWithoutIssuingAConnect() {
        for (error in listOf(IllegalStateException("Background service rejected"), SecurityException("Service unavailable"))) {
            var attempts = 0
            var opened = 0
            VpnTileService.requestStopOrOpenApp({ attempts++; throw error }, { opened++ })
            assertEquals(1, attempts)
            assertEquals(1, opened)
        }
    }

    @Test fun successfulTileStopDoesNotOpenTheAppOrRepeatTheStop() {
        var attempts = 0
        var opened = 0
        VpnTileService.requestStopOrOpenApp({ attempts++ }, { opened++ })
        assertEquals(1, attempts)
        assertEquals(0, opened)
    }

    @Test fun diagnosticsAreOptInBoundedAndNeverExportRawMessages() {
        try {
            DiagnosticEvents.setEnabled(false)
            DiagnosticEvents.record(AppConnectionState(RouteState.Error, TunnelState.Error, "secret-token-timeout"))
            assertTrue(DiagnosticEvents.export().contains("disabled"))
            DiagnosticEvents.setEnabled(true)
            repeat(250) { index ->
                DiagnosticEvents.record(AppConnectionState(RouteState.Error, TunnelState.Error,
                    if (index % 2 == 0) "DNS secret-token" else "timeout private-host"))
            }
            val exported = DiagnosticEvents.export()
            assertTrue(exported.contains("category=timeout"))
            assertFalse(exported.contains("secret-token"))
            assertFalse(exported.contains("private-host"))
            assertTrue(exported.lines().size <= 201)
            DiagnosticEvents.setEnabled(false)
            assertFalse(DiagnosticEvents.export().contains("category="))
        } finally { DiagnosticEvents.setEnabled(false) }
    }

    private class MemoryStore : SecureKeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun putStrings(values: Map<String, String?>) { values.forEach(::putString) }
        override fun remove(vararg keys: String) { keys.forEach(values::remove) }
    }
}
