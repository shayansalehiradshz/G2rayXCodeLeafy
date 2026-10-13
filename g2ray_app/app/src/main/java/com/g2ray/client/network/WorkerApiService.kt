package com.g2ray.client.network

import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface WorkerApiService {
    @POST
    suspend fun wake(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: CodespaceRequest
    ): Response<WorkerWakeResponse>

    @POST
    suspend fun health(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: CodespaceRequest
    ): Response<WorkerWakeResponse>
}

data class CodespaceRequest(
    val codespace_id: String? = null
)

data class WorkerWakeResponse(
    val ok: Boolean? = false,
    val status: Int? = null,
    val codespace_id: String? = null,
    val codespace_label: String? = null,
    val codespace: String? = null,
    val start_accepted: Boolean? = null,
    val state: String? = null,
    val route_ready: Boolean? = null,
    val retry_after_seconds: Int? = null,
    val retry_after_epoch: Long? = null,
    val github_rate_limit_remaining: Int? = null,
    val poll_after_seconds: Int? = null,
    val next_action: String? = null,
    val next_action_code: String? = null,
    val reason: String? = null,
    val error: String? = null,
    val quota_blocked: Boolean? = null,
    val quota_reset_estimate_utc: String? = null,
    val retention_expires_at: String? = null,
    val message: String? = null,
    val route_probe: RouteProbe? = null,
    val route_probes: List<RouteProbe>? = null,
    val preferred_route_port: Int? = null
)

data class RouteProbe(
    val usable: Boolean? = null,
    val http_status: Int? = null,
    val latency_ms: Int? = null,
    val attempts: Int? = null,
    val waited_ms: Int? = null,
    val route_failure_reason: String? = null
)
