package com.g2ray.client.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.g2ray.client.MainActivity
import com.g2ray.client.G2RayApplication
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.vpn.DiagnosticEvents
import com.g2ray.client.vpn.OptionMatrixDeviceTest
import com.g2ray.client.vpn.VpnEnginePreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Uses the owner's saved accounts; never modifies profiles, credentials or routing choices. */
@RunWith(AndroidJUnit4::class)
class UiControlsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun settings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun everyConnectionSwitchAndEnginePersistsThroughTheActualUi() {
        assumeTrue("Requires explicit liveOptions=true", InstrumentationRegistry.getArguments().getString("liveOptions") == "true")
        withRestoredOptions { context, prefs ->
            settings()
            compose.onNodeWithContentDescription("Connection settings").performClick()
            val switches = listOf<Pair<String, () -> Boolean>>(
                "Fast DNS" to prefs::isFastDnsEnabled,
                "IPv4-only compatibility" to prefs::isIpv4OnlyEnabled,
                "DPI compatibility" to prefs::isDpiCompatibilityEnabled,
                "Auto-optimize routes" to prefs::isAutoRouteOptimizationEnabled,
                "Same-Codespace failover" to prefs::isSeamlessFailoverEnabled,
                "Connection diagnostics" to prefs::isDiagnosticsEnabled)
            for ((name, read) in switches) repeat(2) {
                val expected = !read()
                compose.onNodeWithContentDescription(name).performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(5_000L) { read() == expected }
                compose.onNodeWithContentDescription(name).let { if (expected) it.assertIsOn() else it.assertIsOff() }
            }
            for (mode in VpnEngineMode.entries) {
                compose.onNodeWithText(mode.label).performScrollTo().performClick()
                compose.waitUntil(5_000L) { prefs.getMode() == mode }
            }
            // Do not let a recreation launch the owner's optional discovery job.
            if (prefs.isAutoRouteOptimizationEnabled()) {
                compose.onNodeWithContentDescription("Auto-optimize routes").performScrollTo().performClick()
                compose.waitUntil(5_000L) { !prefs.isAutoRouteOptimizationEnabled() }
            }
            val persisted = prefs.readSettings()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Connection settings").assertIsDisplayed()
            assertEquals(persisted, VpnEnginePreferences(context.secureStore).readSettings())
            switches.forEach { (name, read) ->
                compose.onNodeWithContentDescription(name).performScrollTo().let { if (read()) it.assertIsOn() else it.assertIsOff() }
            }
        }
    }

    @Test fun routingDraftValidationAndAppExclusionPersistWithoutConnecting() {
        assumeTrue("Requires explicit liveOptions=true", InstrumentationRegistry.getArguments().getString("liveOptions") == "true")
        withRestoredOptions { context, prefs ->
            @Suppress("DEPRECATION")
            val apps = context.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            assertTrue("YouTube must be launchable for this explicit fixture", apps.any { it.activityInfo.packageName == "com.google.android.youtube" })
            settings()
            compose.onNodeWithContentDescription("Bypass routing").performClick()
            val ir = compose.onNodeWithContentDescription("Bypass .ir domains")
            ir.performScrollTo()
            if (!prefs.getRoutingPolicy().bypassIranianDomains) ir.performClick()
            compose.onNodeWithText("Additional bypass domains").performTextReplacement("example.ir\nexample.com")
            compose.onNodeWithText("Find an app").performTextInput("com.google.android.youtube")
            hideKeyboard()
            val appCheckbox = hasContentDescription("(com.google.android.youtube)", substring = true)
            try {
                compose.waitUntil(15_000L) { compose.onAllNodes(appCheckbox).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                throw AssertionError("Per-app filter did not expose an installed fixture: " + compose.onRoot().printToString(), error)
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(
                appCheckbox)
            val appRow = hasText("com.google.android.youtube") and hasClickAction() and !hasSetTextAction()
            compose.waitUntil(10_000L) { compose.onAllNodes(appRow).fetchSemanticsNodes().isNotEmpty() }
            val expectedExcluded = "com.google.android.youtube" !in prefs.getRoutingPolicy().excludedPackages
            compose.onNode(appRow).performScrollTo().performClick()
            val checkbox = compose.onNode(hasContentDescription("(com.google.android.youtube)", substring = true))
            if (expectedExcluded) checkbox.assertIsOn() else checkbox.assertIsOff()
            checkbox.performClick()
            if (expectedExcluded) checkbox.assertIsOff() else checkbox.assertIsOn()
            checkbox.performClick()
            compose.onNodeWithText("Save routing").assertIsDisplayed().performClick()
            compose.waitUntil(5_000L) { prefs.getRoutingPolicy().bypassDomains == listOf("example.ir", "example.com") }
            compose.onNodeWithText("Saved for the next VPN connection.").assertIsDisplayed()
            val saved = prefs.getRoutingPolicy()
            assertTrue(saved.bypassIranianDomains)
            assertEquals("Per-app row did not toggle its persisted exclusion", expectedExcluded,
                "com.google.android.youtube" in saved.excludedPackages)
            compose.onNodeWithText("Additional bypass domains").performScrollTo().performTextReplacement("https://invalid.example/path")
            hideKeyboard()
            compose.onNodeWithText("Save routing").performClick()
            compose.waitForIdle()
            compose.onNode(hasText("Enter domain names only", substring = true)).assertIsDisplayed()
            assertEquals("Invalid draft replaced the saved policy", saved, prefs.getRoutingPolicy())
        }
    }

    private fun hideKeyboard() = compose.runOnUiThread {
        WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
    }

    private fun withRestoredOptions(action: (G2RayApplication, VpnEnginePreferences) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<G2RayApplication>()
        assertTrue("Stop VPN before changing actual settings", VpnStateRepository.state.value.tunnelState in
            setOf(TunnelState.Disabled, TunnelState.Disconnected))
        val prefs = VpnEnginePreferences(context.secureStore)
        val original = prefs.readSettings()
        val selection = context.accountRepository.activeSelection.value
        val raw = OptionMatrixDeviceTest.OPTION_KEYS.associateWith { context.secureStore.getString(it, null) }
        val backup = File(context.filesDir, "g2ray-option-settings-backup.json")
        check(!backup.exists()) { "Restore the interrupted option test first" }
        backup.writeText(JSONObject(raw.mapValues { it.value ?: JSONObject.NULL }).toString())
        try { action(context, prefs) } finally {
            context.secureStore.putStrings(raw)
            DiagnosticEvents.setEnabled(original.diagnosticsEnabled)
            assertEquals(original, prefs.readSettings())
            assertEquals(selection, context.accountRepository.activeSelection.value)
            kotlin.check(backup.delete())
        }
    }

    @Test fun settingsSystemBackPopsOneLevelAndHelpIsReachable() {
        compose.runOnUiThread { assertNull("The system title bar must not duplicate the Compose toolbar", compose.activity.actionBar) }
        compose.onAllNodes(hasText("Last action:", substring = true)).onFirst().performScrollTo().assertIsDisplayed()
        settings()
        compose.onNodeWithContentDescription("Connection settings").performClick()
        compose.onNodeWithText("Connection settings").assertIsDisplayed()
        listOf(FeatureHelp.Engine, FeatureHelp.FastDns, FeatureHelp.Ipv4, FeatureHelp.Dpi,
            FeatureHelp.Optimize, FeatureHelp.Failover, FeatureHelp.Tile, FeatureHelp.Protection,
            FeatureHelp.Diagnostics).forEach { feature ->
            compose.onNodeWithContentDescription("About ${feature.title}").performScrollTo().performClick()
            compose.onNodeWithText("Got it").assertIsDisplayed().performClick()
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun importActionStaysVisibleForLargeDraftAndRotationPreservesIt() {
        settings()
        compose.onAllNodes(hasText("Configs (", substring = true)).onFirst().performScrollTo().performClick()
        compose.onNodeWithContentDescription("Import Profile").performClick()
        val text = "draft-before-rotation\n" + "not a config\n".repeat(400)
        compose.onNodeWithText("Subscription Text / VLESS URI").performTextInput(text)
        compose.onNodeWithText("Parse Configs").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Parse Configs").assertIsDisplayed()
        compose.onNodeWithText("Subscription Text / VLESS URI").assertTextContains("draft-before-rotation", substring = true)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Import Profile").assertIsDisplayed()
    }

    @Test fun shareShowsQrAndCopyWithoutSelectingAnotherConfig() {
        val repository = ApplicationProvider.getApplicationContext<G2RayApplication>().accountRepository
        val selection = repository.activeSelection.value
        compose.onAllNodes(hasContentDescription("Share config")).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Show QR code").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Scannable config QR")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Scannable config QR").assertIsDisplayed()
        compose.onNodeWithText("Copy link").performClick()
        compose.onNodeWithText("Config copied").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(selection, repository.activeSelection.value)
    }
}
