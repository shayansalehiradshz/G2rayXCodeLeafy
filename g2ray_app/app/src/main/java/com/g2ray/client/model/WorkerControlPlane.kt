package com.g2ray.client.model

data class WorkerControlPlane(
    val workerBaseUrl: String,
    val fallbackWorkerBaseUrl: String? = null,
    val encryptedWakeSecret: String,
    val updatedAt: Long
) {
    fun workerBaseUrls(): List<String> = listOfNotNull(
        workerBaseUrl.takeIf { it.isNotBlank() },
        fallbackWorkerBaseUrl?.takeIf { it.isNotBlank() }
    ).distinct()
}
