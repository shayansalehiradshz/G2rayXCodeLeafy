package com.g2ray.client.model

enum class ApiKeyKind {
    WORKER_WAKE_SECRET,
    GITHUB_TOKEN
}

data class G2RayApiKey(
    val id: String,
    val accountId: String,
    val label: String,
    val kind: ApiKeyKind,
    val encryptedValue: String, // trim/stored securely
    val enabled: Boolean,
    val priority: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?
)
