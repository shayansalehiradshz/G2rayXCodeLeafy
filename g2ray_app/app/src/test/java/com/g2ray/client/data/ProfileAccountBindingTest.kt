package com.g2ray.client.data

import com.g2ray.client.data.AccountRepositoryTest.InMemorySecureStore
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.parser.VlessUriParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileAccountBindingTest {
    @Test
    fun explicitTargetDoesNotFollowTheActiveAccount() {
        val repository = AccountRepository(InMemorySecureStore())
        val first = repository.addAccount("First", "", "first-space")
        val second = repository.addAccount("Second", "", "second-space")
        val selection = repository.activeSelection.value
        assertEquals(first.id, selection.accountId)

        val imported = repository.importProfiles(second.id, listOf(profile("second-space"))).single()

        assertEquals(second.id, imported.accountId)
        assertEquals(selection, repository.activeSelection.value)
        assertTrue(repository.apiKeys.value.isEmpty())
    }

    @Test
    fun knownWrongCodespaceIsRejectedWithoutWritingAnyState() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("First", "", "first-space")
        val before = store.atomicWriteCount
        val selection = repository.activeSelection.value

        assertThrows(IllegalArgumentException::class.java) {
            repository.importProfiles(account.id, listOf(profile("other-space")))
        }

        assertTrue(repository.profiles.value.isEmpty())
        assertEquals(selection, repository.activeSelection.value)
        assertEquals(before, store.atomicWriteCount)
    }

    @Test
    fun mixedCodespaceBatchCannotPartiallyPersist() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("First", "", "first-space")
        val before = store.atomicWriteCount

        assertThrows(IllegalArgumentException::class.java) {
            repository.importProfiles(account.id, listOf(profile("first-space"), profile("other-space")))
        }

        assertTrue(repository.profiles.value.isEmpty())
        assertEquals(before, store.atomicWriteCount)
    }

    @Test
    fun conflictingSniAndHostCannotBorrowTheMatchingIdentity() {
        val repository = AccountRepository(InMemorySecureStore())
        val account = repository.addAccount("First", "", "first-space")
        assertThrows(IllegalArgumentException::class.java) {
            repository.importProfiles(account.id,
                listOf(VlessUriParser.parse(profile("first-space").rawUri
                    .replace("host=first-space-443", "host=other-space-8443")).getOrThrow()))
        }
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun explicitCustomFrontBindingRemainsAvailableAtRepositoryBoundary() {
        val repository = AccountRepository(InMemorySecureStore())
        val account = repository.addAccount("First", "", "first-space")
        val custom = VlessUriParser.parse(profile("first-space").rawUri
            .replace("first-space-443.app.github.dev", "front.example")).getOrThrow()
        assertEquals(account.id, repository.importProfiles(account.id, listOf(custom)).single().accountId)
    }

    @Test
    fun genericAccountDoesNotInventACodespaceIdentity() {
        val repository = AccountRepository(InMemorySecureStore())
        val account = repository.addAccount("Generic", "", null)
        assertEquals(account.id,
            repository.importProfiles(account.id, listOf(profile("other-space"))).single().accountId)
    }

    private fun profile(name: String): G2RayProfile = VlessUriParser.parse(
        "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443" +
            "?encryption=none&security=tls&type=xhttp&alpn=h2&mode=packet-up&path=%2F" +
            "&sni=$name-443.app.github.dev&host=$name-443.app.github.dev"
    ).getOrThrow()
}
