package com.g2ray.client.data

import com.g2ray.client.model.ApiKeyKind
import com.g2ray.client.model.CodespaceWakeMode
import com.g2ray.client.parser.VlessUriParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {

    class InMemorySecureStore : SecureKeyValueStore {
        private val map = mutableMapOf<String, String>()
        var shouldFail = false
        var shouldFailRemove = false
        var atomicWriteCount = 0

        override fun getString(key: String, defaultValue: String?): String? =
            map[key] ?: defaultValue

        override fun putString(key: String, value: String?) {
            if (shouldFail) {
                throw IllegalStateException("Simulated write failure")
            }
            if (value == null) {
                map.remove(key)
            } else {
                map[key] = value
            }
        }

        override fun putStrings(values: Map<String, String?>) {
            if (shouldFail) {
                throw IllegalStateException("Simulated write failure")
            }
            val updated = map.toMutableMap()
            values.forEach { (key, value) ->
                if (value == null) updated.remove(key) else updated[key] = value
            }
            map.clear()
            map.putAll(updated)
            atomicWriteCount += 1
        }

        override fun remove(vararg keys: String) {
            if (shouldFailRemove) {
                throw IllegalStateException("Simulated remove failure")
            }
            keys.forEach { map.remove(it) }
        }

        fun clear() = map.clear()
    }

    @Test
    fun repositoryMutationPersistsAsOneAtomicBatch() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)

        repository.addAccount("Atomic", "https://worker.example", "atomic-space")

        assertEquals(1, store.atomicWriteCount)
        assertEquals("1", store.getString("schema_version"))
        assertNotNull(store.getString("accounts_json"))
        assertNotNull(store.getString("active_selection_json"))
    }

    @Test
    fun testMigrationFromLegacySettings() {
        val store = InMemorySecureStore()
        store.putString("codespace_name", "test-codespace")
        store.putString("wake_secret", "secret-123")
        store.putString("worker_url", "https://example.com")
        store.putString("vpn_config", "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test")

        val repository = AccountRepository(store)

        // Verify migration
        val accounts = repository.accounts.value
        assertEquals(1, accounts.size)
        val migratedAccount = accounts[0]
        assertEquals("Default Account", migratedAccount.displayName)
        assertEquals("https://example.com", migratedAccount.workerBaseUrl)
        assertEquals("test-codespace", migratedAccount.codespaceName)

        val keys = repository.apiKeys.value
        assertEquals(1, keys.size)
        assertEquals("Default Wake Secret", keys[0].label)
        assertEquals("secret-123", keys[0].encryptedValue)

        val profiles = repository.profiles.value
        assertEquals(1, profiles.size)
        assertEquals("test", profiles[0].displayName)

        val selection = repository.activeSelection.value
        assertEquals(migratedAccount.id, selection.accountId)
        assertEquals(keys[0].id, selection.apiKeyId)
        assertEquals(profiles[0].id, selection.profileId)

        // Ensure legacy keys are cleared
        assertNull(store.getString("codespace_name"))
        assertNull(store.getString("wake_secret"))
        assertNull(store.getString("worker_url"))
        assertNull(store.getString("vpn_config"))
    }

    @Test
    fun testMigrationSkipsWhenSchemaAlreadyExists() {
        val store = InMemorySecureStore()
        store.putString("schema_version", "1")
        store.putString("accounts_json", "[]")
        store.putString("codespace_name", "legacy-codespace")
        store.putString("wake_secret", "legacy-secret")
        store.putString("worker_url", "https://legacy.example")

        val repository = AccountRepository(store)

        assertTrue(repository.accounts.value.isEmpty())
        assertNull(repository.loadError.value)
        assertEquals("legacy-codespace", store.getString("codespace_name"))
    }

    @Test
    fun legacyMigrationWriteFailurePreservesDataAndDoesNotCrashConstruction() {
        val store = InMemorySecureStore()
        store.putString("codespace_name", "legacy-space")
        store.putString("wake_secret", "fixture-wake-key")
        store.putString("worker_url", "https://worker.example")
        store.shouldFail = true

        val repository = AccountRepository(store)

        assertNotNull(repository.loadError.value)
        assertTrue(repository.accounts.value.isEmpty())
        assertEquals("fixture-wake-key", store.getString("wake_secret"))
        assertNull(store.getString("schema_version"))
        store.shouldFail = false
        assertThrows(IllegalStateException::class.java) {
            repository.addAccount("Must not bypass migration", "", null)
        }
        assertNull(store.getString("accounts_json"))
    }

    @Test
    fun migrationReadFailureDoesNotEscapeRepositoryConstruction() {
        val store = InMemorySecureStore()
        val failingRead = object : SecureKeyValueStore by store {
            override fun getString(key: String, defaultValue: String?): String? {
                if (key == "schema_version") throw IllegalStateException("Fixture migration read failure")
                return store.getString(key, defaultValue)
            }
        }

        val repository = AccountRepository(failingRead)

        assertNotNull(repository.loadError.value)
        assertEquals(0, store.atomicWriteCount)
    }

    @Test
    fun sharedWorkerMigrationWriteFailureKeepsTheLoadedSnapshotAndStoredJson() {
        val store = InMemorySecureStore()
        val original = AccountRepository(store)
        val account = original.addAccount("Legacy Worker", "https://worker.example", "fixture-space")
        original.addApiKey(account.id, "Wake", "fixture-key", true)
        val accountsBefore = store.getString("accounts_json")
        val keysBefore = store.getString("api_keys_json")
        val selectionBefore = original.activeSelection.value
        val writesBefore = store.atomicWriteCount
        store.shouldFail = true

        val reloaded = AccountRepository(store)

        assertNotNull(reloaded.loadError.value)
        assertEquals(original.accounts.value, reloaded.accounts.value)
        assertEquals(selectionBefore, reloaded.activeSelection.value)
        assertNull(reloaded.workerControlPlane.value)
        assertEquals(writesBefore, store.atomicWriteCount)
        assertEquals(accountsBefore, store.getString("accounts_json"))
        assertEquals(keysBefore, store.getString("api_keys_json"))
        store.shouldFail = false
        assertThrows(IllegalStateException::class.java) { reloaded.selectAccount(account.id) }
    }

    @Test
    fun standaloneLegacyProfileMigratesWithoutInventingAWakeKey() {
        val store = InMemorySecureStore()
        val raw = "vless://00000000-0000-4000-8000-000000000001@fixture.example:443" +
            "?encryption=none&security=tls&type=ws&sni=fixture.example&host=fixture.example&path=%2Fws#Standalone"
        assertTrue(VlessUriParser.parseFirstProfile(raw).isSuccess)
        store.putString("vpn_config", raw)

        val repository = AccountRepository(store)

        assertNull(repository.loadError.value)
        assertEquals(raw, repository.profiles.value.single().rawUri)
        assertEquals(repository.accounts.value.single().id, repository.profiles.value.single().accountId)
        assertEquals(repository.profiles.value.single().id, repository.activeSelection.value.profileId)
        assertTrue(repository.apiKeys.value.isEmpty())
        assertNull(repository.activeSelection.value.apiKeyId)
        assertNull(repository.workerControlPlane.value)
        assertNull(store.getString("vpn_config"))
        assertEquals(repository.profiles.value, AccountRepository(store).profiles.value)
    }

    @Test
    fun unparseableLegacyConfigIsPreservedDuringWakeAccountMigration() {
        val store = InMemorySecureStore()
        val originalConfig = "vless://invalid-legacy-profile#Original"
        assertTrue(VlessUriParser.parseFirstProfile(originalConfig).isFailure)
        store.putString("vpn_config", originalConfig)
        store.putString("worker_url", "https://worker.example")
        store.putString("wake_secret", "fixture-wake-key")

        val repository = AccountRepository(store)

        assertEquals(originalConfig, store.getString("vpn_config"))
        assertEquals(1, repository.accounts.value.size)
        assertEquals(1, repository.apiKeys.value.size)
        assertTrue(repository.profiles.value.isEmpty())
        assertNotNull(repository.loadError.value)
    }

    @Test
    fun reloadRetriesMigrationWithoutDuplicatingRecordsOrChangingOtherSettings() {
        val store = InMemorySecureStore()
        store.putString("worker_url", "https://worker.example")
        store.putString("wake_secret", "fixture-key")
        store.putString("vpn_engine_mode", "xray_native")
        store.shouldFail = true
        val repository = AccountRepository(store)
        assertFalse(repository.savedDataReady.value)

        store.shouldFail = false
        assertTrue(repository.reloadSavedData())
        assertTrue(repository.savedDataReady.value)
        assertNull(repository.loadError.value)
        val accounts = repository.accounts.value
        val keys = repository.apiKeys.value
        val selection = repository.activeSelection.value
        val writes = store.atomicWriteCount
        assertEquals(1, accounts.size)
        assertEquals(1, keys.size)
        assertEquals("fixture-key", repository.workerControlPlane.value?.encryptedWakeSecret)
        assertEquals("xray_native", store.getString("vpn_engine_mode"))

        assertTrue(repository.reloadSavedData())
        assertEquals(accounts, repository.accounts.value)
        assertEquals(keys, repository.apiKeys.value)
        assertEquals(selection, repository.activeSelection.value)
        assertEquals(writes, store.atomicWriteCount)
    }

    @Test
    fun failedReloadDoesNotPublishAPartiallyParsedSnapshot() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Original", "", "fixture-space")
        val parsed = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-4000-8000-000000000001@fixture.example:443" +
                "?encryption=none&security=tls&type=ws&sni=fixture.example&host=fixture.example&path=%2Fws"
        ).getOrThrow()
        repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        val originalAccounts = repository.accounts.value
        val originalProfiles = repository.profiles.value
        val originalSelection = repository.activeSelection.value
        val validProfiles = store.getString("profiles_json")
        val editedAccounts = org.json.JSONArray(store.getString("accounts_json"))
        editedAccounts.getJSONObject(0).put("displayName", "Updated")
        store.putString("accounts_json", editedAccounts.toString())
        store.putString("profiles_json", "{PRIVATE-FIXTURE-MARKER")

        assertFalse(repository.reloadSavedData())
        assertFalse(repository.savedDataReady.value)
        assertEquals(originalAccounts, repository.accounts.value)
        assertEquals(originalProfiles, repository.profiles.value)
        assertEquals(originalSelection, repository.activeSelection.value)
        assertFalse(repository.loadError.value.orEmpty().contains("PRIVATE-FIXTURE-MARKER"))
        assertEquals("{PRIVATE-FIXTURE-MARKER", store.getString("profiles_json"))
        assertThrows(IllegalStateException::class.java) { repository.deleteProfile(originalProfiles.single().id) }

        store.putString("profiles_json", validProfiles)
        assertTrue(repository.reloadSavedData())
        assertEquals("Updated", repository.accounts.value.single().displayName)
        assertEquals(originalProfiles, repository.profiles.value)
        assertNull(repository.loadError.value)
    }

    @Test
    fun unsupportedStandaloneLegacyTextCanBeCopiedAndDoesNotBlockAManualImport() {
        val store = InMemorySecureStore()
        val original = "vless://unsupported-original#Backup"
        store.putString("vpn_config", original)
        val repository = AccountRepository(store)

        assertTrue(repository.savedDataReady.value)
        assertEquals(original, repository.legacyProfileForRecovery.value)
        assertNotNull(repository.loadError.value)
        assertTrue(repository.accounts.value.isEmpty())
        assertTrue(repository.apiKeys.value.isEmpty())
        assertEquals(0, store.atomicWriteCount)
        val account = repository.addAccount("Manual import", "", null)
        val profile = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-4000-8000-000000000001@fixture.example:443" +
                "?encryption=none&security=tls&type=ws&sni=fixture.example&host=fixture.example&path=%2Fws"
        ).getOrThrow()
        repository.importProfile(account.id, profile.name, profile.rawUri, profile)

        assertTrue(repository.reloadSavedData())
        assertNull(repository.loadError.value)
        assertEquals(original, repository.legacyProfileForRecovery.value)
        assertEquals(original, store.getString("vpn_config"))
        assertEquals(profile.rawUri, repository.profiles.value.single().rawUri)
    }

    @Test
    fun testMigrationRemoveFailureDoesNotDuplicateMigratedAccount() {
        val store = InMemorySecureStore()
        store.putString("wake_secret", "secret-123")
        store.putString("worker_url", "https://example.com")
        store.shouldFailRemove = true

        val repo1 = AccountRepository(store)

        assertEquals(1, repo1.accounts.value.size)
        assertTrue(repo1.loadError.value?.contains("failed to remove old keys") == true)

        store.shouldFailRemove = false
        val repo2 = AccountRepository(store)

        assertEquals(1, repo2.accounts.value.size)
    }

    @Test
    fun testAccountCreateEditDelete() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)

        // Create
        val acc = repo.addAccount("My Job", "https://job.com", "my-codespace")
        assertEquals(1, repo.accounts.value.size)
        assertEquals("My Job", repo.accounts.value[0].displayName)
        assertTrue(repo.accounts.value[0].isDefault)

        // Edit
        repo.updateAccount(acc.id, "Edited Job", "https://edited.com", "edited-codespace")
        assertEquals("Edited Job", repo.accounts.value[0].displayName)
        assertEquals("https://edited.com", repo.accounts.value[0].workerBaseUrl)

        // Delete
        repo.deleteAccount(acc.id)
        assertTrue(repo.accounts.value.isEmpty())
    }

    @Test
    fun sharedWorkerControlPlanePersistsWithoutDuplicatingWakeKeys() {
        val store = InMemorySecureStore()
        val repo1 = AccountRepository(store)
        val primary = repo1.addAccount("Primary", "https://legacy.example", "primary-space")
        repo1.addAccount("Standby", "https://legacy.example", "standby-space")
        repo1.setWorkerControlPlane(
            workerBaseUrl = "https://worker.example",
            fallbackWorkerBaseUrl = "https://backup-worker.example",
            wakeSecret = "shared-wake-key"
        )
        repo1.selectAccount(primary.id)

        val repo2 = AccountRepository(store)
        val controlPlane = repo2.workerControlPlane.value
        assertNotNull(controlPlane)
        assertEquals("https://worker.example", controlPlane?.workerBaseUrl)
        assertEquals("https://backup-worker.example", controlPlane?.fallbackWorkerBaseUrl)
        assertEquals("shared-wake-key", controlPlane?.encryptedWakeSecret)
        assertEquals(2, repo2.accounts.value.size)
        assertEquals("primary-space", repo2.accounts.value.single { it.id == primary.id }.workerCodespaceId)
    }

    @Test
    fun testBackupWorkerUrlPersistsAcrossReload() {
        val store = InMemorySecureStore()
        val repo1 = AccountRepository(store)
        val account = repo1.addAccount(
            "Redundant",
            "https://primary.example",
            "codespace",
            "https://backup.example"
        )

        val repo2 = AccountRepository(store)
        val restored = repo2.accounts.value.single { it.id == account.id }

        assertEquals("https://backup.example", restored.fallbackWorkerBaseUrl)
        assertEquals(
            listOf("https://primary.example", "https://backup.example"),
            restored.workerBaseUrls()
        )
    }

    @Test
    fun testMultipleWakeSecretsPerAccount() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val acc = repository.addAccount("Test", "https://example.com", null)

        val key1 = repository.addApiKey(acc.id, "Primary", "secret-primary", true)
        val key2 = repository.addApiKey(acc.id, "Backup", "secret-backup", false)

        val accountKeys = repository.apiKeys.value.filter { it.accountId == acc.id }
        assertEquals(2, accountKeys.size)
        assertEquals("Primary", accountKeys[0].label)
        assertTrue(accountKeys[0].enabled)
        assertEquals("Backup", accountKeys[1].label)
        assertFalse(accountKeys[1].enabled)
    }

    @Test
    fun directGitHubTokenIsTypedAndNeverBecomesTheLegacyWorkerSelection() {
        val repository = AccountRepository(InMemorySecureStore())
        val account = repository.addAccount("Direct", "", "direct-space")

        val token = repository.addApiKey(
            accountId = account.id,
            label = "GitHub",
            secret = "github-token",
            kind = ApiKeyKind.GITHUB_TOKEN
        )
        repository.selectAccount(account.id)

        assertEquals(ApiKeyKind.GITHUB_TOKEN, token.kind)
        assertNull(repository.activeSelection.value.apiKeyId)
        assertEquals("github-token", repository.enabledApiKey(account.id, ApiKeyKind.GITHUB_TOKEN)?.encryptedValue)
        assertNull(repository.enabledApiKey(account.id, ApiKeyKind.WORKER_WAKE_SECRET))
    }

    @Test
    fun accountWakeModeDefaultsToAutoAndDirectTokenPersistsAcrossReload() {
        val store = InMemorySecureStore()
        val repo1 = AccountRepository(store)
        val account = repo1.addAccount("Direct", "", "direct-space")
        assertEquals(CodespaceWakeMode.AUTO, account.wakeMode)

        repo1.updateAccount(
            id = account.id,
            displayName = account.displayName,
            workerBaseUrl = "",
            codespaceName = account.codespaceName,
            wakeMode = CodespaceWakeMode.GITHUB_DIRECT
        )
        repo1.setGitHubToken(account.id, "github-token")

        val repo2 = AccountRepository(store)
        val restored = repo2.accounts.value.single()
        assertEquals(CodespaceWakeMode.GITHUB_DIRECT, restored.wakeMode)
        assertEquals("github-token", repo2.enabledApiKey(account.id, ApiKeyKind.GITHUB_TOKEN)?.encryptedValue)

        repo2.setGitHubToken(account.id, "")
        assertNull(repo2.enabledApiKey(account.id, ApiKeyKind.GITHUB_TOKEN))
    }

    @Test
    fun accountAndGitHubTokenPersistInOneAtomicWrite() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val writesBefore = store.atomicWriteCount

        val account = repository.saveAccountWithGitHubToken(
            id = null,
            displayName = "Direct",
            workerBaseUrl = "",
            codespaceName = "direct-space",
            workerCodespaceId = "direct-space",
            wakeMode = CodespaceWakeMode.GITHUB_DIRECT,
            githubToken = "github-token"
        )

        assertEquals(writesBefore + 1, store.atomicWriteCount)
        val restored = AccountRepository(store)
        assertEquals(account, restored.accounts.value.single())
        assertEquals(
            "github-token",
            restored.enabledApiKey(account.id, ApiKeyKind.GITHUB_TOKEN)?.encryptedValue
        )
    }

    @Test
    fun failedAccountAndGitHubTokenWriteRollsBackBoth() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.saveAccountWithGitHubToken(
            id = null,
            displayName = "Stable",
            workerBaseUrl = "https://worker.example",
            codespaceName = "stable-space",
            workerCodespaceId = "stable-space",
            wakeMode = CodespaceWakeMode.AUTO,
            githubToken = "old-token"
        )
        val accountsBefore = repository.accounts.value
        val keysBefore = repository.apiKeys.value
        store.shouldFail = true

        try {
            repository.saveAccountWithGitHubToken(
                id = account.id,
                displayName = "Changed",
                workerBaseUrl = "https://new-worker.example",
                codespaceName = "changed-space",
                workerCodespaceId = "changed-space",
                wakeMode = CodespaceWakeMode.GITHUB_DIRECT,
                githubToken = "new-token"
            )
            fail("Expected account write failure")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated write failure", e.message)
        } finally {
            store.shouldFail = false
        }

        assertEquals(accountsBefore, repository.accounts.value)
        assertEquals(keysBefore, repository.apiKeys.value)
        val restored = AccountRepository(store)
        assertEquals(accountsBefore, restored.accounts.value)
        assertEquals(keysBefore, restored.apiKeys.value)
    }

    @Test
    fun accountSavedBeforeWakeModeFieldDefaultsToAuto() {
        val store = InMemorySecureStore()
        store.putString("schema_version", "1")
        store.putString(
            "accounts_json",
            """
                [{
                  "id":"legacy-account",
                  "displayName":"Legacy account",
                  "workerBaseUrl":"https://worker.example",
                  "codespaceName":"legacy-codespace",
                  "isDefault":true,
                  "createdAt":1,
                  "updatedAt":1
                }]
            """.trimIndent()
        )
        store.putString("api_keys_json", "[]")
        store.putString("profiles_json", "[]")
        store.putString("active_selection_json", "{\"accountId\":null,\"apiKeyId\":null,\"profileId\":null}")

        val repository = AccountRepository(store)

        assertNull(repository.loadError.value)
        assertEquals(CodespaceWakeMode.AUTO, repository.accounts.value.single().wakeMode)
    }

    @Test
    fun testActiveSelectionSurvivesReload() {
        val store = InMemorySecureStore()
        val repo1 = AccountRepository(store)
        val acc = repo1.addAccount("Survives", "https://example.com", null)
        val key = repo1.addApiKey(acc.id, "Secret", "xyz", true)

        repo1.selectAccount(acc.id)
        repo1.selectApiKey(key.id)

        // Reload from same shared store
        val repo2 = AccountRepository(store)
        assertEquals(acc.id, repo2.activeSelection.value.accountId)
        assertEquals(key.id, repo2.activeSelection.value.apiKeyId)
    }

    @Test
    fun testProfileImportFromMultiLineAndBase64() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val acc = repository.addAccount("ImportAcc", "https://example.com", null)

        val multiLineInput = """
            vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#line1
            vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#line2
        """.trimIndent()

        val parsedList = VlessUriParser.parseAllProfiles(multiLineInput)
        assertEquals(2, parsedList.size)
        assertTrue(parsedList[0].isSuccess)
        assertTrue(parsedList[1].isSuccess)

        repository.importProfile(acc.id, parsedList[0].getOrThrow().name, parsedList[0].getOrThrow().rawUri, parsedList[0].getOrThrow())
        repository.importProfile(acc.id, parsedList[1].getOrThrow().name, parsedList[1].getOrThrow().rawUri, parsedList[1].getOrThrow())

        val accProfiles = repository.profiles.value.filter { it.accountId == acc.id }
        assertEquals(2, accProfiles.size)
        assertEquals("line1", accProfiles[0].displayName)
        assertEquals("line2", accProfiles[1].displayName)
    }

    @Test
    fun bulkProfileImportPersistsInOneAtomicWrite() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Bulk import", "https://example.com", null)
        val parsed = VlessUriParser.parseAllProfiles(
            """
                vless://00000000-0000-0000-0000-000000000000@one.example.com:443?encryption=none&security=tls&sni=one.example.com&type=xhttp&host=one.example.com&path=%2F&mode=packet-up&alpn=h2#one
                vless://00000000-0000-0000-0000-000000000000@two.example.com:443?encryption=none&security=tls&sni=two.example.com&type=ws&host=two.example.com&path=%2Fws#two
            """.trimIndent()
        ).map { it.getOrThrow() }
        val writesBefore = store.atomicWriteCount

        val imported = repository.importProfiles(account.id, parsed)

        assertEquals(writesBefore + 1, store.atomicWriteCount)
        assertEquals(2, imported.size)
        assertEquals(2, repository.profiles.value.count { it.accountId == account.id })
        assertTrue(repository.profiles.value.first().isDefault)
        assertFalse(repository.profiles.value.last().isDefault)
    }

    @Test
    fun bulkProfileImportDeduplicatesInputAndSkipsNoOpWrite() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("No duplicates", "https://example.com", null)
        val parsed = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#same"
        ).getOrThrow()

        val firstBatch = repository.importProfiles(account.id, listOf(parsed, parsed))
        val writesAfterFirstBatch = store.atomicWriteCount
        val secondBatch = repository.importProfiles(account.id, listOf(parsed))

        assertEquals(2, firstBatch.size)
        assertEquals(firstBatch[0].id, firstBatch[1].id)
        assertEquals(firstBatch[0].id, secondBatch.single().id)
        assertEquals(1, repository.profiles.value.count { it.accountId == account.id })
        assertEquals(writesAfterFirstBatch, store.atomicWriteCount)
    }

    @Test
    fun failedBulkProfileImportRollsBackEntireBatch() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Rollback", "https://example.com", null)
        val parsed = VlessUriParser.parseAllProfiles(
            """
                vless://00000000-0000-0000-0000-000000000000@one.example.com:443?encryption=none&security=tls&sni=one.example.com&type=xhttp&host=one.example.com&path=%2F&mode=packet-up&alpn=h2#one
                vless://00000000-0000-0000-0000-000000000000@two.example.com:443?encryption=none&security=tls&sni=two.example.com&type=xhttp&host=two.example.com&path=%2F&mode=packet-up&alpn=h2#two
            """.trimIndent()
        ).map { it.getOrThrow() }
        store.shouldFail = true

        try {
            repository.importProfiles(account.id, parsed)
            fail("Expected bulk import failure")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated write failure", e.message)
        } finally {
            store.shouldFail = false
        }

        assertTrue(repository.profiles.value.isEmpty())
        assertTrue(AccountRepository(store).profiles.value.isEmpty())
    }

    @Test
    fun partialDelaySuccessDoesNotEraseKnownFailuresOrEndpointErrors() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Partial", "https://worker.example", null)
        val parsed = VlessUriParser.parseFirstProfile("vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F").getOrThrow()
        val profile = repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        repository.recordProfileDelay(profile.id, false, null, "timeout")
        repository.recordProfileDelay(profile.id, true, 150L, "two endpoints timed out", successfulSamples = 1, sampleCount = 3)
        val reloaded = AccountRepository(store).profiles.value.single()
        assertEquals(150L, reloaded.lastDelayMs)
        assertEquals(1, reloaded.delayFailureCount)
        assertEquals("two endpoints timed out", reloaded.lastDelayError)
        assertEquals(1, reloaded.lastDelaySuccessfulSamples)
        assertEquals(3, reloaded.lastDelaySampleCount)
    }

    @Test
    fun testProfileDelayHealthIsPersistedAcrossReload() {
        val store = InMemorySecureStore()
        val repo1 = AccountRepository(store)
        val acc = repo1.addAccount("ImportAcc", "https://example.com", null)
        val parsed = VlessUriParser.parseFirstProfile("vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#fast")
            .getOrThrow()
        val profile = repo1.importProfile(acc.id, parsed.name, parsed.rawUri, parsed)

        repo1.recordProfileDelay(
            profile.id,
            success = true,
            delayMs = 87,
            error = null,
            p95Ms = 120,
            jitterMs = 11,
            successfulSamples = 3,
            sampleCount = 3
        )

        val repo2 = AccountRepository(store)
        val reloaded = repo2.profiles.value.single()
        assertEquals(87L, reloaded.lastDelayMs)
        assertEquals(120L, reloaded.lastDelayP95Ms)
        assertEquals(11L, reloaded.lastDelayJitterMs)
        assertEquals(3, reloaded.lastDelaySuccessfulSamples)
        assertEquals(3, reloaded.lastDelaySampleCount)
        assertEquals(0, reloaded.delayFailureCount)
        assertNull(reloaded.lastDelayError)

        repo2.recordProfileDelay(profile.id, success = false, delayMs = null, error = "timeout")

        val repo3 = AccountRepository(store)
        val failed = repo3.profiles.value.single()
        assertNull(failed.lastDelayMs)
        assertEquals(1, failed.delayFailureCount)
        assertEquals("timeout", failed.lastDelayError)
    }

    @Test
    fun measurementNetworkKeysRoundTripAndLegacyHistoryStillLoads() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("History", "https://example.com", null)
        val parsed = VlessUriParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F"
        ).getOrThrow()
        val profile = repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        repository.recordProfileDelay(profile.id, true, 87, null, successfulSamples = 3, sampleCount = 3)
        val legacy = AccountRepository(store)
        assertNull(legacy.loadError.value)
        assertNull(legacy.profiles.value.single().lastDelayNetworkKey)
        assertEquals(87L, legacy.profiles.value.single().lastDelayMs)
        repository.recordProfileDelays(listOf(ProfileDelayUpdate(
            profile.id, true, 90, null, successfulSamples = 3, sampleCount = 3,
            networkKey = "network-a", downloadBps = 4_000_000L, throughputTestedAt = 123_456L
        )))
        val scoped = AccountRepository(store).profiles.value.single()
        assertEquals("network-a", scoped.lastDelayNetworkKey)
        assertEquals("network-a", scoped.lastThroughputNetworkKey)
        assertEquals(4_000_000L, scoped.lastDownloadBps)
        assertEquals(profile.rawUri, scoped.rawUri)
        assertEquals(profile.id, AccountRepository(store).activeSelection.value.profileId)
    }

    @Test
    fun failureCountsRestartOnANewNetworkWithoutErasingAnotherNetworksTransferHistory() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Networks", "https://example.com", null)
        val parsed = VlessUriParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F"
        ).getOrThrow()
        val profile = repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        repeat(2) { repository.recordProfileDelay(profile.id, false, null, "timeout", networkKey = "network-a") }
        assertEquals(2, repository.profiles.value.single().delayFailureCount)
        repository.recordProfileDelays(listOf(ProfileDelayUpdate(
            profile.id, true, 90, null, successfulSamples = 1, sampleCount = 3,
            networkKey = "network-a", downloadBps = 4_000_000L, throughputTestedAt = 123_456L
        )))
        assertEquals(2, repository.profiles.value.single().delayFailureCount)
        repository.recordProfileDelay(profile.id, false, null, "timeout", networkKey = "network-b")
        val firstFailure = AccountRepository(store).profiles.value.single()
        assertEquals(1, firstFailure.delayFailureCount)
        assertEquals("network-b", firstFailure.lastDelayNetworkKey)
        assertEquals("network-a", firstFailure.lastThroughputNetworkKey)
        assertEquals(4_000_000L, firstFailure.lastDownloadBps)
        repository.recordProfileDelay(profile.id, false, null, "timeout", networkKey = "network-b")
        assertEquals(2, repository.profiles.value.single().delayFailureCount)
        assertEquals(4_000_000L, repository.profiles.value.single().lastDownloadBps)
        repository.recordProfileDelay(profile.id, false, null, "timeout", networkKey = "network-a")
        assertEquals(1, repository.profiles.value.single().delayFailureCount)
        assertNull(repository.profiles.value.single().lastDownloadBps)
        assertNull(repository.profiles.value.single().lastThroughputNetworkKey)
    }

    @Test
    fun bulkProfileDelayResultsPersistInOneAtomicWrite() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Bulk", "https://example.com", null)
        fun import(name: String): com.g2ray.client.model.StoredG2RayProfile {
            val parsed = VlessUriParser.parseFirstProfile(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#$name"
            ).getOrThrow()
            return repository.importProfile(account.id, name, parsed.rawUri, parsed)
        }
        val first = import("first")
        val second = import("second")
        val writesBefore = store.atomicWriteCount

        repository.recordProfileDelays(
            listOf(
                ProfileDelayUpdate(
                    id = first.id,
                    success = true,
                    delayMs = 42L,
                    error = null,
                    p95Ms = 51L,
                    jitterMs = 4L,
                    successfulSamples = 3,
                    sampleCount = 3,
                    downloadBps = 4_000_000L,
                    uploadBps = 1_000_000L,
                    throughputTestedAt = 123_456L
                ),
                ProfileDelayUpdate(
                    id = second.id,
                    success = false,
                    delayMs = null,
                    error = "timeout",
                    sampleCount = 2
                )
            )
        )

        assertEquals(writesBefore + 1, store.atomicWriteCount)
        val updated = repository.profiles.value.associateBy { it.id }
        assertEquals(42L, updated.getValue(first.id).lastDelayMs)
        assertEquals(51L, updated.getValue(first.id).lastDelayP95Ms)
        assertEquals(4_000_000L, updated.getValue(first.id).lastDownloadBps)
        assertEquals(1_000_000L, updated.getValue(first.id).lastUploadBps)
        assertEquals(123_456L, updated.getValue(first.id).lastThroughputTestedAt)
        assertEquals("timeout", updated.getValue(second.id).lastDelayError)
        assertEquals(1, updated.getValue(second.id).delayFailureCount)
    }

    @Test
    fun updatingThroughputDoesNotRefreshAnOlderDetailedDelayMeasurement() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("History", "https://example.com", null)
        val parsed = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#history"
        ).getOrThrow()
        val profile = repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        val originalTestedAt = System.currentTimeMillis() - 600_000L
        val delay = ProfileDelayUpdate(
            id = profile.id,
            success = true,
            delayMs = 87L,
            error = null,
            p95Ms = 120L,
            jitterMs = 11L,
            successfulSamples = 3,
            sampleCount = 3,
            delayTestedAt = originalTestedAt
        )
        repository.recordProfileDelays(listOf(delay))
        val transferTestedAt = System.currentTimeMillis()

        repository.recordProfileDelays(
            listOf(delay.copy(downloadBps = 4_000_000L, throughputTestedAt = transferTestedAt))
        )

        val reloaded = AccountRepository(store).profiles.value.single()
        assertEquals(originalTestedAt, reloaded.lastDelayTestedAt)
        assertEquals(120L, reloaded.lastDelayP95Ms)
        assertEquals(3, reloaded.lastDelaySampleCount)
        assertEquals(transferTestedAt, reloaded.lastThroughputTestedAt)
        assertEquals(4_000_000L, reloaded.lastDownloadBps)
    }

    @Test
    fun failedBulkProfileDelayWriteRollsBackEveryProfile() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Rollback", "https://example.com", null)
        val parsed = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#rollback"
        ).getOrThrow()
        val profile = repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        val before = repository.profiles.value
        store.shouldFail = true

        try {
            repository.recordProfileDelays(
                listOf(
                    ProfileDelayUpdate(
                        id = profile.id,
                        success = true,
                        delayMs = 25L,
                        error = null,
                        successfulSamples = 1,
                        sampleCount = 1
                    )
                )
            )
            fail("Expected bulk write failure")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated write failure", e.message)
        } finally {
            store.shouldFail = false
        }

        assertEquals(before, repository.profiles.value)
        assertEquals(before, AccountRepository(store).profiles.value)
    }

    @Test
    fun importingTheExactSameProfileTwiceReusesTheStoredProfileAndHealthHistory() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("No duplicates", "https://example.com", null)
        val parsed = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#same"
        ).getOrThrow()
        val first = repository.importProfile(account.id, parsed.name, parsed.rawUri, parsed)
        repository.recordProfileDelay(first.id, success = true, delayMs = 42, error = null)

        val duplicate = repository.importProfile(account.id, "renamed duplicate", parsed.rawUri, parsed)

        assertEquals(first.id, duplicate.id)
        assertEquals(1, repository.profiles.value.count { it.accountId == account.id })
        assertEquals(42L, repository.profiles.value.single().lastDelayMs)
    }

    @Test
    fun deleteProfiles_removesBatchAndSelectsRemainingFallback() {
        val store = InMemorySecureStore()
        val repository = AccountRepository(store)
        val account = repository.addAccount("Batch", "https://example.com", null)
        repository.selectAccount(account.id)
        fun parsed(name: String) = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#$name"
        ).getOrThrow()
        val firstParsed = parsed("first")
        val secondParsed = parsed("second")
        val remainingParsed = parsed("remaining")
        val first = repository.importProfile(account.id, "first", firstParsed.rawUri, firstParsed)
        val second = repository.importProfile(account.id, "second", secondParsed.rawUri, secondParsed)
        val remaining = repository.importProfile(account.id, "remaining", remainingParsed.rawUri, remainingParsed)
        repository.selectProfile(second.id)

        repository.deleteProfiles(setOf(first.id, second.id))

        assertEquals(listOf(remaining.id), repository.profiles.value.map { it.id })
        assertEquals(remaining.id, repository.activeSelection.value.profileId)
    }

    @Test
    fun testKeyFromDifferentAccountCannotBeSelected() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val accA = repo.addAccount("Acc A", "https://a.com", null)
        val accB = repo.addAccount("Acc B", "https://b.com", null)

        val keyA = repo.addApiKey(accA.id, "Key A", "secret-a", true)
        val keyB = repo.addApiKey(accB.id, "Key B", "secret-b", true)

        // Set Acc A active
        repo.selectAccount(accA.id)

        // Trying to select Key B should fail because its account is Acc B, but active is Acc A
        try {
            repo.selectApiKey(keyB.id)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Success
            assertTrue(e.message?.contains("belong to active account") == true)
        }
    }

    @Test
    fun testProfileFromDifferentAccountCannotBeSelected() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val accA = repo.addAccount("Acc A", "https://a.com", null)
        val accB = repo.addAccount("Acc B", "https://b.com", null)

        val parsed = VlessUriParser.parseFirstProfile("vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test")
        val profileB = repo.importProfile(accB.id, "Profile B", "vless://...", parsed.getOrThrow())

        // Set Acc A active
        repo.selectAccount(accA.id)

        // Selecting profile from Acc B should fail
        try {
            repo.selectProfile(profileB.id)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Success
            assertTrue(e.message?.contains("belong to active account") == true)
        }
    }

    @Test
    fun testDisabledKeyCannotBeSelected() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val acc = repo.addAccount("Acc", "https://example.com", null)
        val key = repo.addApiKey(acc.id, "Backup Key", "secret", false) // disabled

        repo.selectAccount(acc.id)

        try {
            repo.selectApiKey(key.id)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Success
            assertTrue(e.message?.contains("disabled key") == true)
        }
    }

    @Test
    fun testSelectingAccountDoesNotFallbackToDisabledKey() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val acc = repo.addAccount("Acc", "https://example.com", null)
        repo.addApiKey(acc.id, "Disabled", "secret-disabled", false)

        repo.selectAccount(acc.id)

        assertEquals(acc.id, repo.activeSelection.value.accountId)
        assertNull(repo.activeSelection.value.apiKeyId)
    }

    @Test
    fun testDeletingActiveKeyDoesNotFallbackToDisabledKey() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val acc = repo.addAccount("Acc", "https://example.com", null)
        val active = repo.addApiKey(acc.id, "Active", "secret-active", true)
        repo.addApiKey(acc.id, "Disabled", "secret-disabled", false)

        repo.selectAccount(acc.id)
        repo.selectApiKey(active.id)
        repo.deleteApiKey(active.id)

        assertNull(repo.activeSelection.value.apiKeyId)
    }

    @Test
    fun testDisablingActiveKeyClearsSelectionWhenNoEnabledFallbackExists() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val acc = repo.addAccount("Acc", "https://example.com", null)
        val active = repo.addApiKey(acc.id, "Active", "secret-active", true)

        repo.selectAccount(acc.id)
        repo.selectApiKey(active.id)
        repo.updateApiKey(active.id, "Active", enabled = false)

        assertNull(repo.activeSelection.value.apiKeyId)
    }

    @Test
    fun testSaveFailureThrowsException() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)

        // Induce write failure
        store.shouldFail = true

        try {
            repo.addAccount("Fail Acc", "https://fail.com", null)
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated write failure", e.message)
        }
    }

    @Test
    fun testSaveFailureRollsBackInMemoryState() {
        val store = InMemorySecureStore()
        val repo = AccountRepository(store)
        val existing = repo.addAccount("Stable", "https://stable.com", null)

        store.shouldFail = true

        try {
            repo.addAccount("Fail Acc", "https://fail.com", null)
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated write failure", e.message)
        }

        assertEquals(listOf(existing), repo.accounts.value)
        assertEquals(existing.id, repo.activeSelection.value.accountId)
    }

    @Test
    fun checkedStorageFailureAlsoRollsBackThePublishedSnapshot() {
        val backing = InMemorySecureStore()
        var failWrites = false
        val store = object : SecureKeyValueStore by backing {
            override fun putStrings(values: Map<String, String?>) {
                if (failWrites) throw java.io.IOException("Fixture pending-rollback write failure")
                backing.putStrings(values)
            }
        }
        val repository = AccountRepository(store)
        val existing = repository.addAccount("Stable", "", null)
        val originalAccounts = backing.getString("accounts_json")
        val originalSelection = repository.activeSelection.value
        failWrites = true

        assertThrows(java.io.IOException::class.java) {
            repository.addAccount("Must not appear saved", "", null)
        }

        assertEquals(listOf(existing), repository.accounts.value)
        assertEquals(originalSelection, repository.activeSelection.value)
        assertEquals(originalAccounts, backing.getString("accounts_json"))
    }

    @Test
    fun testCorruptedStoredJsonBlocksOverwrite() {
        val store = InMemorySecureStore()
        store.putString("accounts_json", "{broken-json")

        val repo = AccountRepository(store)

        assertTrue(repo.loadError.value?.contains("Failed to load saved accounts") == true)
        try {
            repo.addAccount("Should Fail", "https://fail.com", null)
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Failed to load saved accounts") == true)
        }
        assertEquals("{broken-json", store.getString("accounts_json"))
    }

    @Test
    fun partiallyLoadedStateDoesNotRunSharedWorkerMigration() {
        val store = InMemorySecureStore()
        val initial = AccountRepository(store)
        val account = initial.addAccount("Saved", "https://worker.example", "saved-space")
        initial.addApiKey(account.id, "Wake", "test-wake-key", true)
        store.putString("profiles_json", "{broken-json")
        store.putString("worker_control_plane_json", null)
        val accountsBefore = store.getString("accounts_json")
        val keysBefore = store.getString("api_keys_json")
        val writesBefore = store.atomicWriteCount

        val reloaded = AccountRepository(store)

        assertTrue(reloaded.loadError.value?.contains("Failed to load saved accounts") == true)
        assertNull(reloaded.workerControlPlane.value)
        assertEquals(writesBefore, store.atomicWriteCount)
        assertEquals(accountsBefore, store.getString("accounts_json"))
        assertEquals(keysBefore, store.getString("api_keys_json"))
        assertEquals("{broken-json", store.getString("profiles_json"))
    }
}
