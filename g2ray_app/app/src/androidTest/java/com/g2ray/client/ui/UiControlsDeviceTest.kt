package com.g2ray.client.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.g2ray.client.MainActivity
import com.g2ray.client.G2RayApplication
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the owner's saved accounts; never modifies profiles, credentials or routing choices. */
@RunWith(AndroidJUnit4::class)
class UiControlsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun settings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
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
