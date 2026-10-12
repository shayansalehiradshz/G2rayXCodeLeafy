package com.g2ray.client.model

enum class CodespaceWakeMode {
    AUTO,
    WORKER,
    GITHUB_DIRECT
}

data class G2RayAccount(
    val id: String,
    val displayName: String,
    val workerBaseUrl: String,
    val fallbackWorkerBaseUrl: String? = null,
    val codespaceName: String?,
    val workerCodespaceId: String? = null,
    val wakeMode: CodespaceWakeMode = CodespaceWakeMode.AUTO,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val lastSelectedProfileId: String? = null,
    val lastHealthAt: Long? = null,
    val lastRouteReady: Boolean? = null,
    val quotaResetEstimateUtc: String? = null,
    val retentionExpiresAt: String? = null,
    val lastWorkerMessage: String? = null,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun workerBaseUrls(): List<String> = listOfNotNull(
        workerBaseUrl.takeIf { it.isNotBlank() },
        fallbackWorkerBaseUrl?.takeIf { it.isNotBlank() }
    ).distinct()
}
