package com.g2ray.client.data

import com.g2ray.client.model.*
import com.g2ray.client.parser.CodespaceProfileIdentity
import com.g2ray.client.parser.VlessUriParser
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ProfileDelayUpdate(
    val id: String,
    val success: Boolean,
    val delayMs: Long?,
    val error: String?,
    val p95Ms: Long? = null,
    val jitterMs: Long? = null,
    val successfulSamples: Int = 0,
    val sampleCount: Int = 0,
    val downloadBps: Long? = null,
    val uploadBps: Long? = null,
    val throughputTestedAt: Long? = null,
    val throughputError: String? = null,
    val delayTestedAt: Long? = null,
    val networkKey: String? = null
)

private data class ProfileImportRequest(
    val name: String,
    val rawUri: String,
    val parsed: G2RayProfile
)

class AccountRepository(private val store: SecureKeyValueStore) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listAccountAdapter = moshi.adapter<List<G2RayAccount>>(
        Types.newParameterizedType(List::class.java, G2RayAccount::class.java)
    )
    private val listApiKeyAdapter = moshi.adapter<List<G2RayApiKey>>(
        Types.newParameterizedType(List::class.java, G2RayApiKey::class.java)
    )
    private val listProfileAdapter = moshi.adapter<List<StoredG2RayProfile>>(
        Types.newParameterizedType(List::class.java, StoredG2RayProfile::class.java)
    )
    private val selectionAdapter = moshi.adapter(ActiveSelection::class.java)
    private val controlPlaneAdapter = moshi.adapter(WorkerControlPlane::class.java)

    private val _accounts = MutableStateFlow<List<G2RayAccount>>(emptyList())
    val accounts: StateFlow<List<G2RayAccount>> = _accounts.asStateFlow()

    private val _apiKeys = MutableStateFlow<List<G2RayApiKey>>(emptyList())
    val apiKeys: StateFlow<List<G2RayApiKey>> = _apiKeys.asStateFlow()

    private val _profiles = MutableStateFlow<List<StoredG2RayProfile>>(emptyList())
    val profiles: StateFlow<List<StoredG2RayProfile>> = _profiles.asStateFlow()

    private val _activeSelection = MutableStateFlow(ActiveSelection(null, null, null))
    val activeSelection: StateFlow<ActiveSelection> = _activeSelection.asStateFlow()

    private val _workerControlPlane = MutableStateFlow<WorkerControlPlane?>(null)
    val workerControlPlane: StateFlow<WorkerControlPlane?> = _workerControlPlane.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _savedDataReady = MutableStateFlow(false)
    val savedDataReady: StateFlow<Boolean> = _savedDataReady.asStateFlow()

    private val _legacyProfileForRecovery = MutableStateFlow<String?>(null)
    val legacyProfileForRecovery: StateFlow<String?> = _legacyProfileForRecovery.asStateFlow()

    private var writesBlockedByLoadError = false

    init {
        reloadSavedData()
    }

    @Synchronized
    fun reloadSavedData(): Boolean {
        _savedDataReady.value = false
        loadData()
        if (!writesBlockedByLoadError) {
            try {
                migrateIfNeeded()
                migrateSharedWorkerControlPlaneIfNeeded()
            } catch (e: Exception) {
                writesBlockedByLoadError = true
                _loadError.value = "Saved-data migration could not finish. Existing settings were preserved: ${e.javaClass.simpleName}"
            }
        }
        if (!writesBlockedByLoadError && _loadError.value == null &&
            _legacyProfileForRecovery.value != null && _profiles.value.isEmpty()
        ) {
            _loadError.value = "A legacy config could not be imported. The original text was preserved."
        }
        _savedDataReady.value = !writesBlockedByLoadError
        return _savedDataReady.value
    }

    private fun loadData() {
        try {
            val accountsJson = store.getString("accounts_json", null)
            val loadedAccounts = if (!accountsJson.isNullOrEmpty()) {
                listAccountAdapter.fromJson(accountsJson) ?: emptyList()
            } else {
                emptyList()
            }

            val apiKeysJson = store.getString("api_keys_json", null)
            val loadedApiKeys = if (!apiKeysJson.isNullOrEmpty()) {
                listApiKeyAdapter.fromJson(apiKeysJson) ?: emptyList()
            } else {
                emptyList()
            }

            val profilesJson = store.getString("profiles_json", null)
            val loadedProfiles = if (!profilesJson.isNullOrEmpty()) {
                listProfileAdapter.fromJson(profilesJson) ?: emptyList()
            } else {
                emptyList()
            }

            val selectionJson = store.getString("active_selection_json", null)
            val loadedSelection = if (!selectionJson.isNullOrEmpty()) {
                selectionAdapter.fromJson(selectionJson) ?: ActiveSelection(null, null, null)
            } else {
                ActiveSelection(null, null, null)
            }
            val loadedControlPlane = store.getString("worker_control_plane_json", null)
                ?.takeIf { it.isNotBlank() }
                ?.let(controlPlaneAdapter::fromJson)
            var legacyWarning: String? = null
            val legacyProfile = try {
                store.getString("vpn_config", null)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                legacyWarning = "The legacy config backup could not be read: ${e.javaClass.simpleName}"
                _legacyProfileForRecovery.value
            }

            // Publish only a fully parsed canonical snapshot. A failed reload
            // must not replace part of the last known good in-memory state.
            _accounts.value = loadedAccounts
            _apiKeys.value = loadedApiKeys
            _profiles.value = loadedProfiles
            _activeSelection.value = loadedSelection
            _workerControlPlane.value = loadedControlPlane
            _legacyProfileForRecovery.value = legacyProfile
            _loadError.value = legacyWarning
            writesBlockedByLoadError = false
        } catch (e: Exception) {
            writesBlockedByLoadError = true
            _loadError.value = "Failed to load saved accounts. Your stored data was not overwritten: ${e.javaClass.simpleName}"
        }
    }

    private fun saveData() {
        check(!writesBlockedByLoadError) {
            _loadError.value ?: "Saved account data failed to load; refusing to overwrite it."
        }
        store.putStrings(
            linkedMapOf(
                "accounts_json" to listAccountAdapter.toJson(_accounts.value),
                "api_keys_json" to listApiKeyAdapter.toJson(_apiKeys.value),
                "profiles_json" to listProfileAdapter.toJson(_profiles.value),
                "active_selection_json" to selectionAdapter.toJson(_activeSelection.value),
                "worker_control_plane_json" to _workerControlPlane.value?.let(controlPlaneAdapter::toJson),
                "schema_version" to "1"
            )
        )
    }

    private fun commitState(
        accounts: List<G2RayAccount> = _accounts.value,
        apiKeys: List<G2RayApiKey> = _apiKeys.value,
        profiles: List<StoredG2RayProfile> = _profiles.value,
        selection: ActiveSelection = _activeSelection.value,
        workerControlPlane: WorkerControlPlane? = _workerControlPlane.value
    ) {
        val previousAccounts = _accounts.value
        val previousApiKeys = _apiKeys.value
        val previousProfiles = _profiles.value
        val previousSelection = _activeSelection.value
        val previousWorkerControlPlane = _workerControlPlane.value

        _accounts.value = accounts
        _apiKeys.value = apiKeys
        _profiles.value = profiles
        _activeSelection.value = selection
        _workerControlPlane.value = workerControlPlane

        try {
            saveData()
        } catch (e: Exception) {
            _accounts.value = previousAccounts
            _apiKeys.value = previousApiKeys
            _profiles.value = previousProfiles
            _activeSelection.value = previousSelection
            _workerControlPlane.value = previousWorkerControlPlane
            throw e
        }
    }

    @Synchronized
    fun setWorkerControlPlane(
        workerBaseUrl: String,
        fallbackWorkerBaseUrl: String?,
        wakeSecret: String
    ) {
        val now = System.currentTimeMillis()
        commitState(workerControlPlane = WorkerControlPlane(
            workerBaseUrl = workerBaseUrl.trim(),
            fallbackWorkerBaseUrl = fallbackWorkerBaseUrl?.trim()?.takeIf { it.isNotEmpty() },
            encryptedWakeSecret = wakeSecret.trim(),
            updatedAt = now
        ))
    }

    private fun migrateSharedWorkerControlPlaneIfNeeded() {
        // A later JSON field may fail after accounts and keys were loaded.
        // Do not migrate that partial snapshot or turn a recoverable load
        // error into an exception during application construction.
        if (writesBlockedByLoadError || _workerControlPlane.value != null || _accounts.value.isEmpty()) return
        val selection = _activeSelection.value
        val activeAccount = _accounts.value.firstOrNull { it.id == selection.accountId }
            ?: _accounts.value.first()
        val activeKey = _apiKeys.value.firstOrNull {
            it.id == selection.apiKeyId && it.enabled && it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        } ?: _apiKeys.value.firstOrNull {
            it.accountId == activeAccount.id && it.enabled && it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        } ?: _apiKeys.value.firstOrNull {
            it.enabled && it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        }
        val secret = activeKey?.encryptedValue?.trim().orEmpty()
        val workerUrl = activeAccount.workerBaseUrl.trim()
        if (workerUrl.isBlank() || secret.isBlank()) return
        commitState(workerControlPlane = WorkerControlPlane(
            workerBaseUrl = workerUrl,
            fallbackWorkerBaseUrl = activeAccount.fallbackWorkerBaseUrl?.trim()?.takeIf { it.isNotEmpty() },
            encryptedWakeSecret = secret,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun migrateIfNeeded() {
        if (writesBlockedByLoadError) {
            return
        }

        val schemaVersion = store.getString("schema_version", null)
        val accountsJson = store.getString("accounts_json", null)
        if (!schemaVersion.isNullOrEmpty() || !accountsJson.isNullOrEmpty()) {
            return
        }

        // Migration from legacy settings keys
        val legacyCodespace = store.getString("codespace_name", "") ?: ""
        val legacySecret = store.getString("wake_secret", "") ?: ""
        val legacyWorker = store.getString("worker_url", "") ?: ""
        val legacyVpnConfig = store.getString("vpn_config", "") ?: ""
        val parsedLegacyProfile = legacyVpnConfig.takeIf { it.isNotBlank() }
            ?.let { VlessUriParser.parseFirstProfile(it).getOrNull() }

        if (legacyWorker.isNotBlank() || legacySecret.isNotBlank() || parsedLegacyProfile != null) {
            val accountId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val newAccount = G2RayAccount(
                id = accountId,
                displayName = "Default Account",
                workerBaseUrl = legacyWorker,
                codespaceName = legacyCodespace.takeIf { it.isNotEmpty() },
                isDefault = true,
                createdAt = now,
                updatedAt = now
            )

            val newApiKey = legacySecret.takeIf { it.isNotBlank() }?.let { secret ->
                G2RayApiKey(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    label = "Default Wake Secret",
                    kind = ApiKeyKind.WORKER_WAKE_SECRET,
                    encryptedValue = secret,
                    enabled = true,
                    priority = 1,
                    createdAt = now,
                    updatedAt = now,
                    lastUsedAt = null
                )
            }

            val importedProfile = parsedLegacyProfile?.let { p ->
                StoredG2RayProfile(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    displayName = p.name.takeIf { it.isNotEmpty() } ?: "Default Profile",
                    rawUri = p.rawUri,
                    transport = p.transport,
                    address = p.address,
                    port = p.port,
                    sni = p.sni ?: "",
                    hostHeader = p.hostHeader ?: "",
                    path = p.path,
                    isDefault = true,
                    createdAt = now,
                    updatedAt = now
                )
            }

            commitState(
                accounts = listOf(newAccount),
                apiKeys = listOfNotNull(newApiKey),
                profiles = listOfNotNull(importedProfile),
                selection = ActiveSelection(
                    accountId = accountId,
                    profileId = importedProfile?.id,
                    apiKeyId = newApiKey?.id
                )
            )

            val keysToRemove = mutableListOf("codespace_name", "wake_secret", "worker_url")
            if (legacyVpnConfig.isBlank() || parsedLegacyProfile != null) keysToRemove += "vpn_config"
            runCatching {
                store.remove(*keysToRemove.toTypedArray())
            }.onSuccess {
                if ("vpn_config" in keysToRemove) _legacyProfileForRecovery.value = null
            }.onFailure { e ->
                _loadError.value = "Migrated legacy settings, but failed to remove old keys: ${e.javaClass.simpleName}"
            }
        }
    }

    @Synchronized
    fun addAccount(
        displayName: String,
        workerBaseUrl: String,
        codespaceName: String?,
        fallbackWorkerBaseUrl: String? = null,
        workerCodespaceId: String? = null,
        wakeMode: CodespaceWakeMode = CodespaceWakeMode.AUTO
    ): G2RayAccount {
        val now = System.currentTimeMillis()
        val accountId = UUID.randomUUID().toString()
        val isFirst = _accounts.value.isEmpty()
        val account = G2RayAccount(
            id = accountId,
            displayName = displayName,
            workerBaseUrl = workerBaseUrl,
            fallbackWorkerBaseUrl = fallbackWorkerBaseUrl,
            codespaceName = codespaceName,
            workerCodespaceId = workerCodespaceId?.trim()?.takeIf { it.isNotEmpty() } ?: codespaceName,
            wakeMode = wakeMode,
            isDefault = isFirst,
            createdAt = now,
            updatedAt = now
        )
        val nextAccounts = _accounts.value + account
        val nextSelection = if (isFirst) {
            ActiveSelection(accountId = accountId, profileId = null, apiKeyId = null)
        } else {
            _activeSelection.value
        }
        commitState(accounts = nextAccounts, selection = nextSelection)
        return account
    }

    @Synchronized
    fun saveAccountWithGitHubToken(
        id: String?,
        displayName: String,
        workerBaseUrl: String,
        codespaceName: String?,
        fallbackWorkerBaseUrl: String? = null,
        workerCodespaceId: String? = null,
        wakeMode: CodespaceWakeMode = CodespaceWakeMode.AUTO,
        githubToken: String = ""
    ): G2RayAccount {
        val now = System.currentTimeMillis()
        val existing = id?.let { accountId ->
            requireNotNull(_accounts.value.firstOrNull { it.id == accountId }) { "Account not found" }
        }
        val isFirst = existing == null && _accounts.value.isEmpty()
        val account = existing?.copy(
            displayName = displayName,
            workerBaseUrl = workerBaseUrl,
            fallbackWorkerBaseUrl = fallbackWorkerBaseUrl,
            codespaceName = codespaceName,
            workerCodespaceId = workerCodespaceId?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing.workerCodespaceId
                ?: codespaceName,
            wakeMode = wakeMode,
            updatedAt = now
        ) ?: G2RayAccount(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            workerBaseUrl = workerBaseUrl,
            fallbackWorkerBaseUrl = fallbackWorkerBaseUrl,
            codespaceName = codespaceName,
            workerCodespaceId = workerCodespaceId?.trim()?.takeIf { it.isNotEmpty() } ?: codespaceName,
            wakeMode = wakeMode,
            isDefault = isFirst,
            createdAt = now,
            updatedAt = now
        )
        val nextAccounts = if (existing == null) {
            _accounts.value + account
        } else {
            _accounts.value.map { if (it.id == account.id) account else it }
        }
        val nextSelection = if (isFirst) {
            ActiveSelection(accountId = account.id, profileId = null, apiKeyId = null)
        } else {
            _activeSelection.value
        }
        val nextKeys = githubTokenKeys(
            currentKeys = _apiKeys.value,
            accountId = account.id,
            token = githubToken,
            now = now
        )

        commitState(accounts = nextAccounts, apiKeys = nextKeys, selection = nextSelection)
        return account
    }

    @Synchronized
    fun updateAccount(
        id: String,
        displayName: String,
        workerBaseUrl: String,
        codespaceName: String?,
        fallbackWorkerBaseUrl: String? = null,
        workerCodespaceId: String? = null,
        wakeMode: CodespaceWakeMode = CodespaceWakeMode.AUTO
    ) {
        val nextAccounts = _accounts.value.map {
            if (it.id == id) {
                it.copy(
                    displayName = displayName,
                    workerBaseUrl = workerBaseUrl,
                    fallbackWorkerBaseUrl = fallbackWorkerBaseUrl,
                    codespaceName = codespaceName,
                    workerCodespaceId = workerCodespaceId?.trim()?.takeIf { value -> value.isNotEmpty() }
                        ?: it.workerCodespaceId
                        ?: codespaceName,
                    wakeMode = wakeMode,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                it
            }
        }
        commitState(accounts = nextAccounts)
    }

    @Synchronized
    fun deleteAccount(id: String) {
        val nextAccounts = _accounts.value.filter { it.id != id }
        val nextApiKeys = _apiKeys.value.filter { it.accountId != id }
        val nextProfiles = _profiles.value.filter { it.accountId != id }

        val currentSelection = _activeSelection.value
        val nextSelection = if (currentSelection.accountId == id) {
            val nextAccount = nextAccounts.firstOrNull()
            ActiveSelection(
                accountId = nextAccount?.id,
                profileId = nextProfiles.firstOrNull { it.accountId == nextAccount?.id }?.id,
                apiKeyId = nextApiKeys
                    .filter {
                        it.accountId == nextAccount?.id && it.enabled &&
                            it.kind == ApiKeyKind.WORKER_WAKE_SECRET
                    }
                    .minByOrNull { it.priority }
                    ?.id
            )
        } else {
            currentSelection
        }
        commitState(nextAccounts, nextApiKeys, nextProfiles, nextSelection)
    }

    @Synchronized
    fun selectAccount(id: String) {
        val accountExists = _accounts.value.any { it.id == id }
        if (accountExists) {
            val rememberedProfile = _accounts.value.firstOrNull { it.id == id }?.lastSelectedProfileId
                ?.let { profileId -> _profiles.value.firstOrNull { it.id == profileId && it.accountId == id } }
            val defaultProfile = rememberedProfile
                ?: _profiles.value.firstOrNull { it.accountId == id && it.isDefault }
                ?: _profiles.value.firstOrNull { it.accountId == id }
            val defaultKey = _apiKeys.value
                .filter {
                    it.accountId == id && it.enabled && it.kind == ApiKeyKind.WORKER_WAKE_SECRET
                }
                .minByOrNull { it.priority }

            commitState(selection = ActiveSelection(
                accountId = id,
                profileId = defaultProfile?.id,
                apiKeyId = defaultKey?.id
            ))
        }
    }

    @Synchronized
    fun addApiKey(
        accountId: String,
        label: String,
        secret: String,
        enabled: Boolean = true,
        kind: ApiKeyKind = ApiKeyKind.WORKER_WAKE_SECRET
    ): G2RayApiKey {
        val now = System.currentTimeMillis()
        val keyId = UUID.randomUUID().toString()
        val isFirstWorkerKeyForAccount = kind == ApiKeyKind.WORKER_WAKE_SECRET &&
            _apiKeys.value.none { it.accountId == accountId && it.kind == ApiKeyKind.WORKER_WAKE_SECRET }
        val apiKey = G2RayApiKey(
            id = keyId,
            accountId = accountId,
            label = label,
            kind = kind,
            encryptedValue = secret,
            enabled = enabled,
            priority = _apiKeys.value.count { it.accountId == accountId && it.kind == kind } + 1,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null
        )
        val nextApiKeys = _apiKeys.value + apiKey
        val nextSelection = if (isFirstWorkerKeyForAccount && enabled && _activeSelection.value.accountId == accountId) {
            _activeSelection.value.copy(apiKeyId = keyId)
        } else {
            _activeSelection.value
        }
        commitState(apiKeys = nextApiKeys, selection = nextSelection)
        return apiKey
    }

    @Synchronized
    fun updateApiKey(id: String, label: String, enabled: Boolean, secret: String? = null) {
        val nextApiKeys = _apiKeys.value.map {
            if (it.id == id) {
                it.copy(
                    label = label,
                    enabled = enabled,
                    encryptedValue = secret ?: it.encryptedValue,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                it
            }
        }
        val selection = _activeSelection.value
        val nextSelection = if (!enabled && selection.apiKeyId == id) {
            val activeAccId = selection.accountId
            selection.copy(
                apiKeyId = nextApiKeys
                    .filter {
                        it.accountId == activeAccId && it.enabled && it.kind == ApiKeyKind.WORKER_WAKE_SECRET
                    }
                    .minByOrNull { it.priority }
                    ?.id
            )
        } else {
            selection
        }
        commitState(apiKeys = nextApiKeys, selection = nextSelection)
    }

    @Synchronized
    fun deleteApiKey(id: String) {
        val nextApiKeys = _apiKeys.value.filter { it.id != id }
        val selection = _activeSelection.value
        val nextSelection = if (selection.apiKeyId == id) {
            val activeAccId = selection.accountId
            val fallbackKey = nextApiKeys
                .filter {
                    it.accountId == activeAccId && it.enabled && it.kind == ApiKeyKind.WORKER_WAKE_SECRET
                }
                .minByOrNull { it.priority }
            selection.copy(apiKeyId = fallbackKey?.id)
        } else {
            selection
        }
        commitState(apiKeys = nextApiKeys, selection = nextSelection)
    }

    @Synchronized
    fun selectApiKey(id: String) {
        val selection = _activeSelection.value
        val key = _apiKeys.value.find { it.id == id }
            ?: throw IllegalArgumentException("Key not found")

        require(key.accountId == selection.accountId) {
            "Key does not belong to active account"
        }
        require(key.enabled) {
            "Cannot select a disabled key"
        }
        require(key.kind == ApiKeyKind.WORKER_WAKE_SECRET) {
            "Only Worker wake keys can be selected as the active legacy key"
        }

        commitState(selection = selection.copy(apiKeyId = id))
    }

    @Synchronized
    fun enabledApiKey(accountId: String, kind: ApiKeyKind): G2RayApiKey? {
        return _apiKeys.value
            .asSequence()
            .filter { it.accountId == accountId && it.kind == kind && it.enabled }
            .minWithOrNull(compareBy<G2RayApiKey> { it.priority }.thenBy { it.createdAt })
    }

    @Synchronized
    fun setGitHubToken(accountId: String, token: String) {
        require(_accounts.value.any { it.id == accountId }) { "Account not found" }
        commitState(
            apiKeys = githubTokenKeys(
                currentKeys = _apiKeys.value,
                accountId = accountId,
                token = token,
                now = System.currentTimeMillis()
            )
        )
    }

    private fun githubTokenKeys(
        currentKeys: List<G2RayApiKey>,
        accountId: String,
        token: String,
        now: Long
    ): List<G2RayApiKey> {
        val normalizedToken = token.trim()
        val existing = currentKeys.firstOrNull {
            it.accountId == accountId && it.kind == ApiKeyKind.GITHUB_TOKEN
        }
        val withoutGitHubTokens = currentKeys.filterNot {
            it.accountId == accountId && it.kind == ApiKeyKind.GITHUB_TOKEN
        }
        return if (normalizedToken.isEmpty()) {
            withoutGitHubTokens
        } else {
            withoutGitHubTokens + (existing?.copy(
                label = "GitHub Direct Token",
                encryptedValue = normalizedToken,
                enabled = true,
                priority = 1,
                updatedAt = now
            ) ?: G2RayApiKey(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                label = "GitHub Direct Token",
                kind = ApiKeyKind.GITHUB_TOKEN,
                encryptedValue = normalizedToken,
                enabled = true,
                priority = 1,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = null
            ))
        }
    }

    @Synchronized
    fun importProfile(accountId: String, name: String, rawUri: String, parsed: G2RayProfile): StoredG2RayProfile {
        return importProfilesInternal(
            accountId,
            listOf(ProfileImportRequest(name = name, rawUri = rawUri, parsed = parsed))
        ).single()
    }

    @Synchronized
    fun importProfiles(accountId: String, parsedProfiles: List<G2RayProfile>): List<StoredG2RayProfile> {
        return importProfilesInternal(
            accountId,
            parsedProfiles.map { parsed ->
                ProfileImportRequest(name = parsed.name, rawUri = parsed.rawUri, parsed = parsed)
            }
        )
    }

    private fun importProfilesInternal(
        accountId: String,
        requests: List<ProfileImportRequest>
    ): List<StoredG2RayProfile> {
        val account = requireNotNull(_accounts.value.firstOrNull { it.id == accountId }) { "Account not found" }
        if (requests.isEmpty()) return emptyList()

        account.codespaceName?.trim()?.takeIf { it.isNotEmpty() }?.let { expected ->
            // Validate the whole batch before persisting anything. Custom fronts
            // have no inferable GitHub identity and remain explicitly bound.
            require(requests.none { CodespaceProfileIdentity.conflictsWith(it.parsed, expected) }) {
                "A config belongs to a different Codespace. Choose the matching Codespace before importing."
            }
        }

        val knownByUri = linkedMapOf<String, StoredG2RayProfile>()
        _profiles.value.asSequence()
            .filter { it.accountId == accountId }
            .forEach { knownByUri.putIfAbsent(it.rawUri.trim(), it) }

        val now = System.currentTimeMillis()
        val hadProfiles = knownByUri.isNotEmpty()
        val newProfiles = mutableListOf<StoredG2RayProfile>()
        val imported = requests.map { request ->
            val normalizedRawUri = request.rawUri.trim()
            knownByUri[normalizedRawUri] ?: StoredG2RayProfile(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                displayName = request.name,
                rawUri = normalizedRawUri,
                transport = request.parsed.transport,
                address = request.parsed.address,
                port = request.parsed.port,
                sni = request.parsed.sni ?: "",
                hostHeader = request.parsed.hostHeader ?: "",
                path = request.parsed.path,
                isDefault = !hadProfiles && newProfiles.isEmpty(),
                createdAt = now,
                updatedAt = now
            ).also { stored ->
                knownByUri[normalizedRawUri] = stored
                newProfiles += stored
            }
        }

        if (newProfiles.isNotEmpty()) {
            val nextSelection = if (!hadProfiles && _activeSelection.value.accountId == accountId) {
                _activeSelection.value.copy(profileId = newProfiles.first().id)
            } else {
                _activeSelection.value
            }
            commitState(profiles = _profiles.value + newProfiles, selection = nextSelection)
        }
        return imported
    }

    @Synchronized
    fun deleteProfile(id: String) {
        deleteProfiles(setOf(id))
    }

    @Synchronized
    fun deleteProfiles(ids: Set<String>) {
        if (ids.isEmpty()) return
        val nextProfiles = _profiles.value.filterNot { it.id in ids }
        val selection = _activeSelection.value
        val nextSelection = if (selection.profileId in ids) {
            val activeAccId = selection.accountId
            val fallbackProfile = nextProfiles.firstOrNull { it.accountId == activeAccId }
            selection.copy(profileId = fallbackProfile?.id)
        } else {
            selection
        }
        val now = System.currentTimeMillis()
        val nextAccounts = _accounts.value.map { account ->
            if (account.id == nextSelection.accountId && account.lastSelectedProfileId in ids) {
                account.copy(lastSelectedProfileId = nextSelection.profileId, updatedAt = now)
            } else {
                account
            }
        }
        commitState(accounts = nextAccounts, profiles = nextProfiles, selection = nextSelection)
    }

    @Synchronized
    fun selectProfile(id: String) {
        val selection = _activeSelection.value
        val profile = _profiles.value.find { it.id == id }
            ?: throw IllegalArgumentException("Profile not found")

        require(profile.accountId == selection.accountId) {
            "Profile does not belong to active account"
        }

        val nextAccounts = _accounts.value.map { account ->
            if (account.id == selection.accountId) account.copy(
                lastSelectedProfileId = id,
                updatedAt = System.currentTimeMillis()
            ) else account
        }
        commitState(accounts = nextAccounts, selection = selection.copy(profileId = id))
    }

    @Synchronized
    fun recordCodespaceHealth(
        accountId: String,
        routeReady: Boolean?,
        quotaResetEstimateUtc: String?,
        retentionExpiresAt: String?,
        message: String?
    ) {
        val now = System.currentTimeMillis()
        val nextAccounts = _accounts.value.map { account ->
            if (account.id == accountId) account.copy(
                lastHealthAt = now,
                lastRouteReady = routeReady,
                quotaResetEstimateUtc = quotaResetEstimateUtc ?: account.quotaResetEstimateUtc,
                retentionExpiresAt = retentionExpiresAt ?: account.retentionExpiresAt,
                lastWorkerMessage = message,
                updatedAt = now
            ) else account
        }
        commitState(accounts = nextAccounts)
    }

    fun recordProfileDelay(
        id: String,
        success: Boolean,
        delayMs: Long?,
        error: String?,
        p95Ms: Long? = null,
        jitterMs: Long? = null,
        successfulSamples: Int = 0,
        sampleCount: Int = 0,
        networkKey: String? = null
    ) {
        recordProfileDelays(
            listOf(
                ProfileDelayUpdate(
                    id = id,
                    success = success,
                    delayMs = delayMs,
                    error = error,
                    p95Ms = p95Ms,
                    jitterMs = jitterMs,
                    successfulSamples = successfulSamples,
                    sampleCount = sampleCount,
                    networkKey = networkKey
                )
            )
        )
    }

    @Synchronized
    fun recordProfileDelays(updates: List<ProfileDelayUpdate>) {
        if (updates.isEmpty()) return
        val updatesById = updates.associateBy { it.id }
        require(updatesById.size == updates.size) {
            "Duplicate profile delay result"
        }
        val existingIds = _profiles.value.mapTo(hashSetOf()) { it.id }
        val missingIds = updatesById.keys - existingIds
        require(missingIds.isEmpty()) {
            "Profile not found: ${missingIds.joinToString()}"
        }

        val now = System.currentTimeMillis()
        val nextProfiles = _profiles.value.map { profile ->
            val update = updatesById[profile.id]
            if (update == null) {
                profile
            } else {
                val delayTestedAt = update.delayTestedAt?.coerceIn(0L, now) ?: now
                val partial = update.sampleCount > 0 && update.successfulSamples < update.sampleCount
                val sameNetwork = profile.lastDelayNetworkKey == update.networkKey
                val sameThroughputNetwork = profile.lastThroughputNetworkKey == update.networkKey
                val previousFailures = if (sameNetwork) profile.delayFailureCount else 0
                val delayUpdated = if (update.success && update.delayMs != null) {
                    profile.copy(
                        lastDelayMs = update.delayMs,
                        lastDelayTestedAt = delayTestedAt,
                        lastDelayNetworkKey = update.networkKey,
                        lastDelayP95Ms = update.p95Ms,
                        lastDelayJitterMs = update.jitterMs,
                        lastDelaySuccessfulSamples = update.successfulSamples.coerceAtLeast(0),
                        lastDelaySampleCount = update.sampleCount.coerceAtLeast(0),
                        delayFailureCount = if (partial) previousFailures else 0,
                        lastDelayError = if (partial) {
                            update.error?.takeIf { it.isNotBlank() }
                                ?: "Only ${update.successfulSamples}/${update.sampleCount} delay samples succeeded"
                        } else null,
                        updatedAt = now
                    )
                } else {
                    profile.copy(
                        lastDelayMs = null,
                        lastDelayTestedAt = delayTestedAt,
                        lastDelayNetworkKey = update.networkKey,
                        lastDelayP95Ms = null,
                        lastDelayJitterMs = null,
                        lastDelaySuccessfulSamples = 0,
                        lastDelaySampleCount = update.sampleCount.coerceAtLeast(0),
                        delayFailureCount = if (previousFailures == Int.MAX_VALUE) {
                            Int.MAX_VALUE
                        } else {
                            previousFailures + 1
                        },
                        lastDelayError = update.error?.takeIf { it.isNotBlank() } ?: "Delay test failed",
                        lastDownloadBps = if (sameThroughputNetwork) null else profile.lastDownloadBps,
                        lastUploadBps = if (sameThroughputNetwork) null else profile.lastUploadBps,
                        lastThroughputTestedAt = if (sameThroughputNetwork) null else profile.lastThroughputTestedAt,
                        lastThroughputError = if (sameThroughputNetwork) null else profile.lastThroughputError,
                        lastThroughputNetworkKey = if (sameThroughputNetwork) null else profile.lastThroughputNetworkKey,
                        updatedAt = now
                    )
                }
                if (update.throughputTestedAt != null) {
                    delayUpdated.copy(
                        lastDownloadBps = update.downloadBps?.takeIf { it > 0L },
                        lastUploadBps = update.uploadBps?.takeIf { it > 0L },
                        lastThroughputTestedAt = update.throughputTestedAt.coerceAtLeast(0L),
                        lastThroughputNetworkKey = update.networkKey,
                        lastThroughputError = update.throughputError?.takeIf { it.isNotBlank() },
                        updatedAt = now
                    )
                } else {
                    delayUpdated
                }
            }
        }
        commitState(profiles = nextProfiles)
    }

    @Synchronized
    fun updateApiKeyLastUsed(id: String) {
        val nextApiKeys = _apiKeys.value.map {
            if (it.id == id) {
                it.copy(lastUsedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        commitState(apiKeys = nextApiKeys)
    }
}
