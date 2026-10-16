package com.g2ray.client.ui

import androidx.lifecycle.ViewModelStore
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.AccountRepositoryTest.InMemorySecureStore
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.vpn.VpnEnginePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = G2RayApplication::class, sdk = [31])
class ProfileImportViewModelTest {
    @Test
    fun codespaceNameMustMatchAnEntireGithubOriginNotASubstring() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id, uri("space-extra"))
        assertTrue(result.isFailure)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun suffixLookalikeCannotPassTheGithubOriginCheck() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id, uri("space").replace("app.github.dev", "app.github.dev.evil.example"))
        assertTrue(result.isFailure)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun mixedSelectionIsRejectedInsteadOfSilentlyDroppingOtherCodespaces() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id, uri("space") + "\n" + uri("other"))
        assertTrue(result.isFailure)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun matchingSniDoesNotHideAConflictingHttpHost() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id,
            uri("space").replace("host=space-443", "host=other-443"))
        assertTrue(result.isFailure)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun explicitImportTargetDoesNotSwitchTheActiveAccount() = withImporter { viewModel, repository ->
        val first = repository.addAccount("First", "", "first-space")
        val second = repository.addAccount("Second", "", "second-space")
        val result = import(viewModel, second.id, uri("second-space"))
        assertEquals(1, result.getOrThrow())
        assertEquals(first.id, repository.activeSelection.value.accountId)
        assertEquals(second.id, repository.profiles.value.single().accountId)
    }

    @Test
    fun caseInsensitiveOriginsAndExplicitHostPortRemainCompatible() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id,
            uri("SPACE").replace("host=SPACE-443.app.github.dev", "host=space-443.app.github.dev%3A443"))
        assertEquals(1, result.getOrThrow())
        assertEquals(account.id, repository.profiles.value.single().accountId)
    }

    @Test
    fun customFrontRequiresConfirmationForANamedCodespace() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id, uri("space").replace("space-443.app.github.dev", "front.example"))
        assertTrue(result.isFailure)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun confirmedCustomFrontKeepsItsExplicitTargetWithoutSwitchingActiveAccount() = withImporter { viewModel, repository ->
        val first = repository.addAccount("First", "", "first-space")
        val second = repository.addAccount("Second", "", "second-space")
        val result = import(viewModel, second.id,
            uri("second-space").replace("second-space-443.app.github.dev", "front.example"), allowUnidentified = true)
        assertEquals(1, result.getOrThrow())
        assertEquals(first.id, repository.activeSelection.value.accountId)
        assertEquals(second.id, repository.profiles.value.single().accountId)
    }

    @Test
    fun customFrontConfirmationCannotAuthorizeAKnownDifferentCodespace() = withImporter { viewModel, repository ->
        val account = repository.addAccount("Target", "", "space")
        val result = import(viewModel, account.id, uri("other"), allowUnidentified = true)
        assertTrue(result.isFailure)
        assertTrue(repository.profiles.value.isEmpty())
    }

    private fun uri(name: String) =
        "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443" +
            "?encryption=none&security=tls&type=xhttp&alpn=h2&mode=packet-up&path=%2F" +
            "&sni=$name-443.app.github.dev&host=$name-443.app.github.dev"

    private fun import(viewModel: G2RayViewModel, accountId: String, raw: String, allowUnidentified: Boolean = false): Result<Int> {
        val result = CompletableDeferred<Result<Int>>()
        viewModel.importProfilesText(accountId, raw, allowUnidentifiedOrigins = allowUnidentified) { result.complete(it) }
        return runBlocking { withTimeout(10_000L) { result.await() } }
    }

    private fun withImporter(block: (G2RayViewModel, AccountRepository) -> Unit) {
        val application = RuntimeEnvironment.getApplication() as G2RayApplication
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(repository))
        val previous = VpnStateRepository.state.value
        VpnStateRepository.state.value = AppConnectionState(RouteState.Unknown, TunnelState.Disconnected, "Fixture")
        val viewModelStore = ViewModelStore()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = G2RayViewModel(application, { error("No native probe is expected") }, VpnEnginePreferences(store))
            viewModelStore.put("fixture", viewModel)
            block(viewModel, repository)
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
            VpnStateRepository.state.value = previous
        }
    }
}
