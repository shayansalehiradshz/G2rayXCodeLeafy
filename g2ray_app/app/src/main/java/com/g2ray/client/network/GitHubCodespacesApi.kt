package com.g2ray.client.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

interface GitHubCodespacesApiService {
    @GET("user/codespaces/{codespaceName}")
    suspend fun getCodespace(
        @Path("codespaceName") codespaceName: String,
        @Header("Authorization") authorization: String
    ): Response<GitHubCodespaceResponse>

    @POST("user/codespaces/{codespaceName}/start")
    suspend fun startCodespace(
        @Path("codespaceName") codespaceName: String,
        @Header("Authorization") authorization: String
    ): Response<GitHubCodespaceResponse>
}

data class GitHubCodespaceResponse(
    val name: String? = null,
    val state: String? = null,
    val pending_operation: Boolean? = null,
    val last_used_at: String? = null,
    val idle_timeout_minutes: Int? = null,
    val retention_period_minutes: Int? = null,
    val retention_expires_at: String? = null
)

private data class GitHubErrorResponse(
    val message: String? = null,
    val documentation_url: String? = null
)

object GitHubNetwork {
    private const val API_BASE_URL = "https://api.github.com/"
    private const val API_VERSION = "2022-11-28"
    private const val ACCEPT = "application/vnd.github+json"
    private const val USER_AGENT = "G2Ray-Android/1.0"

    fun createApiService(
        loggingEnabled: Boolean,
        protectSocket: ((Socket) -> Boolean)? = null,
        dnsLookup: ((String) -> List<InetAddress>)? = null
    ): GitHubCodespacesApiService {
        val client: OkHttpClient = WorkerNetwork.createHttpClient(
            loggingEnabled,
            protectSocket,
            dnsLookup
        )
            .newBuilder()
            // GitHub lifecycle calls are short control-plane requests. Keep a
            // stalled API path from making a user wait for the route deadline.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", ACCEPT)
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubCodespacesApiService::class.java)
    }
}

enum class GitHubCodespacePhase {
    CHECKING,
    STARTING,
    WAITING
}

data class GitHubCodespaceProgress(
    val phase: GitHubCodespacePhase,
    val message: String,
    val attempt: Int
)

sealed interface GitHubCodespaceOutcome {
    data class Ready(val response: GitHubCodespaceResponse) : GitHubCodespaceOutcome

    data class Failed(
        val message: String,
        val controlPlaneUnavailable: Boolean = false,
        val httpCode: Int? = null,
        val response: GitHubCodespaceResponse? = null
    ) : GitHubCodespaceOutcome

    data class TimedOut(val message: String) : GitHubCodespaceOutcome
}

class GitHubCodespaceCoordinator(
    private val apiService: GitHubCodespacesApiService,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val errorAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(GitHubErrorResponse::class.java)

    suspend fun ensureAvailable(
        codespaceName: String,
        token: String,
        maxWaitMs: Long = 3 * 60 * 1000L,
        maxAttempts: Int = 60,
        onProgress: (GitHubCodespaceProgress) -> Unit = {}
    ): GitHubCodespaceOutcome {
        val normalizedName = codespaceName.trim()
        val normalizedToken = token.trim()
        if (normalizedName.isEmpty()) {
            return GitHubCodespaceOutcome.Failed("GitHub Codespace name is empty.")
        }
        if (normalizedToken.isEmpty()) {
            return GitHubCodespaceOutcome.Failed("GitHub token is empty.")
        }

        val authorization = "Bearer $normalizedToken"
        val startedAt = nowMs()
        var attempts = 0
        var startRequested = false
        var consecutiveServerErrors = 0
        var startServerErrors = 0

        while (attempts < maxAttempts && nowMs() - startedAt < maxWaitMs) {
            attempts++
            onProgress(
                GitHubCodespaceProgress(
                    phase = if (startRequested) GitHubCodespacePhase.WAITING else GitHubCodespacePhase.CHECKING,
                    message = if (startRequested) {
                        "Waiting for GitHub to make the Codespace available..."
                    } else {
                        "Checking Codespace state with GitHub..."
                    },
                    attempt = attempts
                )
            )

            val statusResponse = try {
                apiService.getCodespace(normalizedName, authorization)
            } catch (error: IOException) {
                return GitHubCodespaceOutcome.Failed(
                    message = "Could not reach GitHub: ${error.localizedMessage ?: "network unavailable"}",
                    controlPlaneUnavailable = true
                )
            }

            if (!statusResponse.isSuccessful) {
                if (statusResponse.code() in 500..599) {
                    consecutiveServerErrors++
                    if (consecutiveServerErrors >= 3) {
                        return failureFrom(statusResponse, controlPlaneUnavailable = true)
                    }
                    sleep((consecutiveServerErrors * 1_000L).coerceAtMost(3_000L))
                    continue
                }
                return failureFrom(statusResponse)
            }
            consecutiveServerErrors = 0

            val codespace = statusResponse.body()
                ?: return GitHubCodespaceOutcome.Failed("GitHub returned an empty Codespace response.")
            when (codespace.state.orEmpty().lowercase()) {
                "available" -> if (codespace.pending_operation != true) {
                    return GitHubCodespaceOutcome.Ready(codespace)
                }
                "running" -> if (codespace.pending_operation != true) {
                    return GitHubCodespaceOutcome.Ready(codespace)
                }
                "deleted", "archived", "failed" -> {
                    return GitHubCodespaceOutcome.Failed(
                        message = "Codespace state is ${codespace.state}; GitHub cannot start it.",
                        response = codespace
                    )
                }
                "shutdown", "stopped" -> {
                    if (!startRequested) {
                        onProgress(
                            GitHubCodespaceProgress(
                                phase = GitHubCodespacePhase.STARTING,
                                message = "Starting Codespace through GitHub...",
                                attempt = attempts
                            )
                        )
                        val startResponse = try {
                            apiService.startCodespace(normalizedName, authorization)
                        } catch (error: IOException) {
                            return GitHubCodespaceOutcome.Failed(
                                message = "GitHub start request failed: ${error.localizedMessage ?: "network unavailable"}",
                                controlPlaneUnavailable = true
                            )
                        }
                        if (startResponse.isSuccessful) {
                            startRequested = true
                            startServerErrors = 0
                            val started = startResponse.body()
                            if (
                                started != null &&
                                started.state.equals("available", ignoreCase = true) &&
                                started.pending_operation != true
                            ) {
                                return GitHubCodespaceOutcome.Ready(started)
                            }
                        } else if (startResponse.code() in setOf(304, 409)) {
                            startRequested = true
                            startServerErrors = 0
                        } else if (startResponse.code() in 500..599) {
                            startServerErrors++
                            if (startServerErrors >= 3) {
                                return failureFrom(startResponse, controlPlaneUnavailable = true)
                            }
                            sleep((startServerErrors * 1_000L).coerceAtMost(3_000L))
                            continue
                        } else {
                            return failureFrom(startResponse)
                        }
                    }
                }
            }

            sleep(POLL_INTERVAL_MS)
        }

        return GitHubCodespaceOutcome.TimedOut(
            "GitHub did not make the Codespace available within ${maxWaitMs / 1000}s."
        )
    }

    private fun failureFrom(
        response: Response<GitHubCodespaceResponse>,
        controlPlaneUnavailable: Boolean = false
    ): GitHubCodespaceOutcome.Failed {
        val bodyMessage = runCatching {
            response.errorBody()?.string()?.let(errorAdapter::fromJson)?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val message = when (response.code()) {
            400 -> "GitHub rejected the Codespace lifecycle request."
            401 -> "GitHub token is invalid or expired."
            402 -> "GitHub quota or billing blocked the Codespace."
            403 -> if (response.headers()["X-RateLimit-Remaining"] == "0") {
                val reset = response.headers()["X-RateLimit-Reset"]
                "GitHub API rate limit is exhausted${reset?.let { " until Unix time $it" }.orEmpty()}."
            } else if (
                bodyMessage?.contains("secondary rate limit", ignoreCase = true) == true ||
                bodyMessage?.contains("abuse detection", ignoreCase = true) == true ||
                response.headers()["Retry-After"] != null
            ) {
                "GitHub temporarily limited this request. Wait and retry instead of changing the token."
            } else {
                "GitHub token needs Codespaces read and Codespaces lifecycle admin write permission."
            }
            404 -> "Codespace was not found, or this GitHub token cannot access it."
            409 -> "GitHub is already changing the Codespace state. Retry shortly."
            429 -> "GitHub API rate limit is active. Retry later."
            in 500..599 -> "GitHub Codespaces API is temporarily unavailable."
            else -> "GitHub Codespaces request failed with HTTP ${response.code()}."
        }
        return GitHubCodespaceOutcome.Failed(
            message = if (bodyMessage == null || message.contains(bodyMessage, ignoreCase = true)) {
                message
            } else {
                "$message GitHub: $bodyMessage"
            },
            controlPlaneUnavailable = controlPlaneUnavailable || response.code() in 500..599,
            httpCode = response.code()
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}
